"""Step 5: run the marking model over every downloaded tile of an area.

    python 05_infer.py --area hannover [--classical]
Output: <data>/masks/<tile>.png   marking probability 0..255

--classical skips the network and writes the bootstrap detector's output instead (useful to test the
later steps before a model is trained, and as a baseline to compare against).
"""
import argparse
import importlib
import time

import cv2
import numpy as np
import torch

from common import AREAS, TILE_PX, TileId, area_utm, load_roads, road_mask, sub

PAD = 64


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--area", default="test", choices=sorted(AREAS))
    ap.add_argument("--classical", action="store_true")
    ap.add_argument("--overwrite", action="store_true")
    a = ap.parse_args()

    dop, masks = sub("dop"), sub("masks")
    ax0, ay0, ax1, ay1 = area_utm(a.area)
    tiles = [p for p in sorted(dop.glob("*.jpg"))
             if (b := TileId.parse(p.stem).bbox)[2] > ax0 and b[0] < ax1 and b[3] > ay0 and b[1] < ay1]
    tiles = [p for p in tiles if a.overwrite or not (masks / f"{p.stem}.png").exists()]
    print(f"{len(tiles)} tiles to process")

    if a.classical:
        detect = importlib.import_module("03_pseudo_labels").detect
        roads = load_roads(a.area)
        for p in tiles:
            lab = detect(cv2.imread(str(p)), road_mask(TileId.parse(p.stem), roads))
            cv2.imwrite(str(masks / f"{p.stem}.png"), np.where(lab == 255, 255, 0).astype(np.uint8))
        return

    import segmentation_models_pytorch as smp
    from importlib import import_module
    to_tensor = import_module("04_train").to_tensor
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    model = smp.Unet("resnet34", encoder_weights=None, classes=1)
    model.load_state_dict(torch.load(sub("model") / "marking_unet.pt", map_location=dev))
    model.to(dev).eval()
    t0 = time.time()
    with torch.no_grad():
        for k, p in enumerate(tiles, 1):
            img = cv2.imread(str(p))
            if img is None or img.shape[:2] != (TILE_PX, TILE_PX):
                continue
            x = to_tensor(cv2.copyMakeBorder(img, PAD, PAD, PAD, PAD, cv2.BORDER_REFLECT))[None].to(dev)
            with torch.autocast(dev, enabled=dev == "cuda"):
                prob = torch.sigmoid(model(x))[0, 0, PAD:-PAD, PAD:-PAD].float().cpu().numpy()
            cv2.imwrite(str(masks / f"{p.stem}.png"), (prob * 255).astype(np.uint8))
            if k % 100 == 0:
                print(f"{k}/{len(tiles)}  {(time.time() - t0) / k:.2f} s/tile")
    print(f"done -> {masks}")


if __name__ == "__main__":
    main()
