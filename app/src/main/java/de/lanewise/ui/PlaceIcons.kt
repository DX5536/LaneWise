package de.lanewise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.CarRepair
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Church
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.Icecream
import androidx.compose.material.icons.rounded.LocalAtm
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.LocalPharmacy
import androidx.compose.material.icons.rounded.LocalPostOffice
import androidx.compose.material.icons.rounded.Museum
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.lanewise.settings.PlaceKind
import de.lanewise.settings.SavedPlace

/**
 * Icons for places (Material icons by Google, Apache 2.0): by OSM category for search results (restaurant, gym,
 * shop …), and a palette of icons and colours to pick from when saving a place.
 */
object PlaceIcons {

    /** Icons offered when saving a place, by stable id (stored in [SavedPlace.icon]). */
    val PICKABLE: List<Pair<String, ImageVector>> = listOf(
        "star" to Icons.Rounded.Star, "home" to Icons.Rounded.Home, "work" to Icons.Rounded.Work,
        "heart" to Icons.Rounded.Favorite, "family" to Icons.Rounded.Groups, "restaurant" to Icons.Rounded.Restaurant,
        "cafe" to Icons.Rounded.LocalCafe, "shopping" to Icons.Rounded.ShoppingBag, "grocery" to Icons.Rounded.LocalGroceryStore,
        "gym" to Icons.Rounded.FitnessCenter, "school" to Icons.Rounded.School, "kids" to Icons.Rounded.ChildCare,
        "health" to Icons.Rounded.LocalHospital, "fuel" to Icons.Rounded.LocalGasStation, "charging" to Icons.Rounded.EvStation,
        "parking" to Icons.Rounded.LocalParking, "bank" to Icons.Rounded.AccountBalance, "park" to Icons.Rounded.Park,
        "sport" to Icons.Rounded.SportsSoccer, "pets" to Icons.Rounded.Pets, "party" to Icons.Rounded.Celebration,
        "train" to Icons.Rounded.Train, "flight" to Icons.Rounded.Flight, "place" to Icons.Rounded.Place,
    )

    /** Colours offered when saving a place (ARGB): lime, violet, yellow, orange, red, pink, blue, teal, grey. */
    val COLORS: List<Long> = listOf(
        0xFFB8F33A, 0xFF8C72FF, 0xFFFFC83D, 0xFFFF8A3D, 0xFFE5484D, 0xFFFF6FB5, 0xFF4C8DF5, 0xFF2BC4B0, 0xFF9AA3B5,
    )

    const val STAR_YELLOW = 0xFFFFC83D
    private const val FOOD = 0xFFFF8A3D
    private const val SHOP = 0xFF4C8DF5
    private const val HEALTH = 0xFFE5484D
    private const val LEISURE = 0xFF2BB673
    private const val TRANSPORT = 0xFF8C72FF
    private const val MONEY = 0xFFF5B83D
    private const val OTHER = 0xFF9AA3B5

    fun byId(id: String?): ImageVector? = PICKABLE.firstOrNull { it.first == id }?.second

    /**
     * Icon and colour for an OSM category "key:value" (Photon `osm_key` / `osm_value`, or the tags of a smart-stop
     * result), e.g. "amenity:restaurant" → fork and knife in orange; addresses and unknown kinds → a map pin.
     */
    fun forCategory(category: String?): Pair<ImageVector, Long> {
        val key = category?.substringBefore(':').orEmpty()
        val v = category?.substringAfter(':', "").orEmpty()
        return when (key) {
            "amenity" -> when (v) {
                "restaurant", "food_court" -> Icons.Rounded.Restaurant to FOOD
                "cafe" -> Icons.Rounded.LocalCafe to FOOD
                "fast_food" -> Icons.Rounded.Fastfood to FOOD
                "ice_cream" -> Icons.Rounded.Icecream to FOOD
                "bar", "pub", "biergarten", "nightclub" -> Icons.Rounded.LocalBar to FOOD
                "bank", "bureau_de_change" -> Icons.Rounded.AccountBalance to MONEY
                "atm" -> Icons.Rounded.LocalAtm to MONEY
                "pharmacy" -> Icons.Rounded.LocalPharmacy to HEALTH
                "hospital", "clinic", "doctors", "dentist" -> Icons.Rounded.LocalHospital to HEALTH
                "fuel" -> Icons.Rounded.LocalGasStation to TRANSPORT
                "charging_station" -> Icons.Rounded.EvStation to TRANSPORT
                "parking", "parking_entrance" -> Icons.Rounded.LocalParking to TRANSPORT
                "bus_station" -> Icons.Rounded.DirectionsBus to TRANSPORT
                "school", "university", "college" -> Icons.Rounded.School to OTHER
                "kindergarten", "childcare" -> Icons.Rounded.ChildCare to OTHER
                "place_of_worship" -> Icons.Rounded.Church to OTHER
                "cinema", "theatre" -> Icons.Rounded.Theaters to LEISURE
                "library" -> Icons.Rounded.LocalLibrary to OTHER
                "post_office" -> Icons.Rounded.LocalPostOffice to OTHER
                "veterinary" -> Icons.Rounded.Pets to HEALTH
                else -> Icons.Rounded.Place to OTHER
            }
            "shop" -> when (v) {
                "supermarket", "convenience", "greengrocer", "butcher" -> Icons.Rounded.LocalGroceryStore to SHOP
                "bakery", "pastry" -> Icons.Rounded.BakeryDining to FOOD
                "hairdresser", "beauty" -> Icons.Rounded.ContentCut to SHOP
                "car_repair", "car", "tyres" -> Icons.Rounded.CarRepair to TRANSPORT
                "florist", "garden_centre" -> Icons.Rounded.LocalFlorist to LEISURE
                "mall", "department_store", "clothes", "shoes", "fashion", "boutique" -> Icons.Rounded.ShoppingBag to SHOP
                "chemist", "pharmacy" -> Icons.Rounded.LocalPharmacy to HEALTH
                else -> Icons.Rounded.Storefront to SHOP
            }
            "leisure" -> when (v) {
                "fitness_centre", "sports_centre", "fitness_station" -> Icons.Rounded.FitnessCenter to LEISURE
                "swimming_pool", "water_park" -> Icons.Rounded.Pool to LEISURE
                "pitch", "stadium", "sports_hall" -> Icons.Rounded.SportsSoccer to LEISURE
                "park", "garden", "playground", "nature_reserve" -> Icons.Rounded.Park to LEISURE
                else -> Icons.Rounded.Park to LEISURE
            }
            "tourism" -> when (v) {
                "hotel", "hostel", "guest_house", "motel", "apartment" -> Icons.Rounded.Hotel to TRANSPORT
                "museum", "gallery" -> Icons.Rounded.Museum to LEISURE
                else -> Icons.Rounded.Place to LEISURE
            }
            "railway", "public_transport" -> Icons.Rounded.Train to TRANSPORT
            "highway" -> if (v == "bus_stop") Icons.Rounded.DirectionsBus to TRANSPORT else Icons.Rounded.Place to OTHER
            "aeroway" -> Icons.Rounded.Flight to TRANSPORT
            "healthcare" -> Icons.Rounded.LocalHospital to HEALTH
            "building" -> if (v == "house" || v == "residential" || v == "apartments") Icons.Rounded.Home to OTHER else Icons.Rounded.Place to OTHER
            else -> Icons.Rounded.Place to OTHER
        }
    }

    /** Icon and colour of a saved place: its own pick, else Home / Work / yellow star. */
    fun forSaved(p: SavedPlace): Pair<ImageVector, Long> {
        val default = when (p.kind) {
            PlaceKind.HOME -> Icons.Rounded.Home to 0xFFB8F33A
            PlaceKind.WORK -> Icons.Rounded.Work to 0xFF8C72FF
            PlaceKind.FAVORITE -> Icons.Rounded.Star to STAR_YELLOW
        }
        return (byId(p.icon) ?: default.first) to (p.color ?: default.second)
    }
}

/** Round badge: [icon] in [color] on a soft tint of it. */
@Composable
fun PlaceBadge(icon: ImageVector, color: Long, modifier: Modifier = Modifier, size: Dp = 38.dp, contentDescription: String? = null) {
    val c = Color(color)
    Box(modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, tint = c, modifier = Modifier.size(size * 0.58f))
    }
}
