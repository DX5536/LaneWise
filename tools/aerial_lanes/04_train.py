"""Step 4: train a road-marking segmentation model (U-Net, ResNet-34 encoder) on the GPU.

    python 04_train.py [--epochs 30] [--crop 512] [--batch 8]
Labels: <data>/labels_manual/*.png (hand-corrected, preferred) else <data>/labels_auto/*.png.
Output: <data>/model/marking_unet.pt  (+ marking_unet.json with training stats)

Hand-corrected tiles are sampled 4x more often and validation uses them when there are any, so the
reported IoU measures agreement with human labels rather than with the bootstrap detector.
"""
import argparse
import json
import random
import time

import cv2
import numpy as np
import segmentation_models_pytorch as smp
import torch
from torch.utils.data import DataLoader, Dataset

from common import data_dir, sub

MEAN = np.array([0.485, 0.456, 0.406], np.float32) * 255
STD = np.array([0.229, 0.224, 0.225], np.float32) * 255


def to_tensor(img_bgr: np.ndarray) -> torch.Tensor:
    rgb = img_bgr[..., ::-1].astype(np.float32)
    return torch.from_numpy(((rgb - MEAN) / STD).transpose(2, 0, 1).copy())


class Tiles(Dataset):
    def __init__(self, items, crop, n, augment):
        self.items, self.crop, self.n, self.augment = items, crop, n, augment

    def __len__(self):
        return self.n

    def __getitem__(self, idx):
        rnd = random.Random() if self.augment else random.Random(idx)
        img_p, lab_p = self.items[rnd.randrange(len(self.items))]
        img = cv2.imread(str(img_p))
        lab = cv2.imread(str(lab_p), cv2.IMREAD_GRAYSCALE)
        h, w = lab.shape
        # Prefer crops that contain road (label != ignore).
        for _ in range(10):
            y, x = rnd.randrange(h - self.crop + 1), rnd.randrange(w - self.crop + 1)
            if (lab[y:y + self.crop, x:x + self.crop] != 127).mean() > 0.1:
                break
        img = img[y:y + self.crop, x:x + self.crop]
        lab = lab[y:y + self.crop, x:x + self.crop]
        if self.augment:
            k = rnd.randrange(4)
            img, lab = np.rot90(img, k), np.rot90(lab, k)
            if rnd.random() < 0.5:
                img, lab = img[:, ::-1], lab[:, ::-1]
            img = np.clip(img.astype(np.float32) * rnd.uniform(0.7, 1.3) + rnd.uniform(-20, 20), 0, 255).astype(np.uint8)
        target = torch.from_numpy((lab == 255).astype(np.float32))[None]
        valid = torch.from_numpy((lab != 127).astype(np.float32))[None]
        return to_tensor(np.ascontiguousarray(img)), target, valid


def loss_fn(logits, target, valid):
    bce = torch.nn.functional.binary_cross_entropy_with_logits(logits, target, reduction="none", pos_weight=torch.tensor(5.0, device=logits.device))
    bce = (bce * valid).sum() / valid.sum().clamp(min=1)
    p = torch.sigmoid(logits) * valid
    t = target * valid
    dice = 1 - (2 * (p * t).sum() + 1) / (p.sum() + t.sum() + 1)
    return bce + dice


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--crop", type=int, default=512)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--steps", type=int, default=400, help="crops per epoch = steps * batch")
    a = ap.parse_args()

    dop, auto, manual = sub("dop"), sub("labels_auto"), sub("labels_manual")
    man = [(dop / f"{p.stem}.jpg", p) for p in sorted(manual.glob("*.png")) if (dop / f"{p.stem}.jpg").exists()]
    aut = [(dop / f"{p.stem}.jpg", p) for p in sorted(auto.glob("*.png"))
           if (dop / f"{p.stem}.jpg").exists() and not (manual / p.name).exists()]
    if not man and not aut:
        raise SystemExit("no labels - run 03_pseudo_labels.py first")
    random.Random(0).shuffle(aut)
    if man:
        val = man[: max(1, len(man) // 5)]
        train = man[len(val):] * 4 + aut
    else:
        val = aut[: max(1, len(aut) // 10)]
        train = aut[len(val):]
    print(f"train tiles: {len(train)} (manual {len(man)}), val tiles: {len(val)} ({'manual' if man else 'auto'})")

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    model = smp.Unet("resnet34", encoder_weights="imagenet", classes=1).to(dev)
    opt = torch.optim.AdamW(model.parameters(), lr=3e-4, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=1e-3, total_steps=a.epochs * a.steps)
    scaler = torch.amp.GradScaler(enabled=dev == "cuda")
    tl = DataLoader(Tiles(train, a.crop, a.steps * a.batch, True), batch_size=a.batch, num_workers=4, persistent_workers=True)
    vl = DataLoader(Tiles(val, a.crop, 64, False), batch_size=a.batch, num_workers=2)

    out = sub("model")
    best, hist = -1.0, []
    for ep in range(1, a.epochs + 1):
        model.train()
        t0, tot = time.time(), 0.0
        for x, y, v in tl:
            x, y, v = x.to(dev), y.to(dev), v.to(dev)
            with torch.autocast(dev, enabled=dev == "cuda"):
                loss = loss_fn(model(x), y, v)
            opt.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.step(opt)
            scaler.update()
            sched.step()
            tot += loss.item()
        model.eval()
        inter = union = 0.0
        with torch.no_grad():
            for x, y, v in vl:
                x, y, v = x.to(dev), y.to(dev), v.to(dev)
                with torch.autocast(dev, enabled=dev == "cuda"):
                    p = (torch.sigmoid(model(x)) > 0.5).float()
                inter += (p * y * v).sum().item()
                union += (((p + y) > 0).float() * v).sum().item()
        iou = inter / max(union, 1)
        hist.append({"epoch": ep, "loss": tot / a.steps, "val_iou": iou})
        print(f"epoch {ep:3d}  loss {tot / a.steps:.4f}  val IoU {iou:.3f}  {time.time() - t0:.0f}s")
        if iou > best:
            best = iou
            torch.save(model.state_dict(), out / "marking_unet.pt")
    (out / "marking_unet.json").write_text(json.dumps({"best_val_iou": best, "val_on": "manual" if man else "auto",
                                                       "train_tiles": len(train), "history": hist}, indent=1))
    print(f"best val IoU {best:.3f} -> {out / 'marking_unet.pt'}  (data dir {data_dir()})")


if __name__ == "__main__":
    main()
