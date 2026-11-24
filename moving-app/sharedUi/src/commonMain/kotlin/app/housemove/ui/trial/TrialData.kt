package app.housemove.ui.trial

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.automirrored.filled.MenuBook
import app.housemove.ui.icons.filled.Archive
import app.housemove.ui.icons.filled.Badge
import app.housemove.ui.icons.filled.Bathtub
import app.housemove.ui.icons.filled.Bed
import app.housemove.ui.icons.filled.Bedtime
import app.housemove.ui.icons.filled.Build
import app.housemove.ui.icons.filled.Cable
import app.housemove.ui.icons.filled.Chair
import app.housemove.ui.icons.filled.Checkroom
import app.housemove.ui.icons.filled.CleaningServices
import app.housemove.ui.icons.filled.Description
import app.housemove.ui.icons.filled.DirectionsCar
import app.housemove.ui.icons.filled.DoorFront
import app.housemove.ui.icons.filled.DryCleaning
import app.housemove.ui.icons.filled.Fastfood
import app.housemove.ui.icons.filled.FitnessCenter
import app.housemove.ui.icons.filled.Handyman
import app.housemove.ui.icons.filled.Inventory2
import app.housemove.ui.icons.filled.Key
import app.housemove.ui.icons.filled.Keyboard
import app.housemove.ui.icons.filled.Lightbulb
import app.housemove.ui.icons.filled.LocalFireDepartment
import app.housemove.ui.icons.filled.LocalFlorist
import app.housemove.ui.icons.filled.Luggage
import app.housemove.ui.icons.filled.PhoneAndroid
import app.housemove.ui.icons.filled.PhotoLibrary
import app.housemove.ui.icons.filled.Print
import app.housemove.ui.icons.filled.Restaurant
import app.housemove.ui.icons.filled.Roofing
import app.housemove.ui.icons.filled.Schedule
import app.housemove.ui.icons.filled.ShoppingBag
import app.housemove.ui.icons.filled.ShoppingBasket
import app.housemove.ui.icons.filled.Star
import app.housemove.ui.icons.filled.Straighten
import app.housemove.ui.icons.filled.TableChart
import app.housemove.ui.icons.filled.Toys
import app.housemove.ui.icons.filled.Tv
import app.housemove.ui.icons.filled.Warehouse
import app.housemove.ui.icons.filled.WaterDrop
import app.housemove.ui.icons.filled.WbSunny
import app.housemove.ui.icons.filled.Weekend
import app.housemove.ui.icons.filled.WineBar

/*
 * Made-up rooms and things for the trial screen: enough rows (204) to scroll for a while on a phone, with the
 * kinds of names, colours and notes the real lists have. Not the app's data model.
 */

internal class TrialRoom(val name: String, val code: String, val color: Color)

internal class TrialRow(val index: Int, val title: String, val icon: ImageVector, val room: TrialRoom, val note: String)

// The app's room colours (ROOM_COLORS in the shared module), so the chips look like the app's.
private val Red = Color(0xFFD32F2F)
private val Blue = Color(0xFF1976D2)
private val Green = Color(0xFF2E7D32)
private val Yellow = Color(0xFFFBC02D)
private val Orange = Color(0xFFEF6C00)
private val Purple = Color(0xFF7B1FA2)
private val Pink = Color(0xFFE91E63)
private val Teal = Color(0xFF00897B)
private val Brown = Color(0xFF6D4C41)
private val Grey = Color(0xFF616161)
private val Lime = Color(0xFFC0CA33)
private val Navy = Color(0xFF283593)

private class Things(val room: TrialRoom, val things: List<Pair<String, ImageVector>>)

private val houseContents: List<Things> by lazy {
    val box = Icons.Filled.Inventory2
    val fragile = Icons.Filled.WineBar
    val furniture = Icons.Filled.Chair
    listOf(
        Things(TrialRoom("Kitchen", "KIT", Red), listOf(
            "Kettle" to Icons.Filled.Restaurant, "Toaster" to Icons.Filled.Restaurant, "Plates" to fragile,
            "Glasses" to fragile, "Pans" to box, "Cutlery tray" to Icons.Filled.Restaurant, "Spices" to box,
            "Mixing bowls" to box, "Coffee machine" to Icons.Filled.Restaurant, "Wine glasses" to fragile,
            "Baking trays" to box, "Tea towels" to Icons.Filled.DryCleaning, "Fridge food" to Icons.Filled.Fastfood,
            "Knife block" to Icons.Filled.Restaurant, "Mugs" to fragile, "Cookbooks" to Icons.AutoMirrored.Filled.MenuBook,
            "Kitchen bin" to Icons.Filled.CleaningServices,
        )),
        Things(TrialRoom("Living room", "LIV", Blue), listOf(
            "Sofa" to Icons.Filled.Weekend, "Armchair" to furniture, "Television" to Icons.Filled.Tv,
            "Speakers" to Icons.Filled.Cable, "Floor lamp" to Icons.Filled.Lightbulb, "Big rug" to Icons.Filled.Straighten,
            "Coffee table" to Icons.Filled.TableChart, "Bookshelf" to Icons.AutoMirrored.Filled.MenuBook,
            "Board games" to Icons.Filled.Toys, "Cushions" to Icons.Filled.Weekend, "Photo frames" to Icons.Filled.PhotoLibrary,
            "House plants" to Icons.Filled.LocalFlorist, "Curtains" to Icons.Filled.DryCleaning, "Side table" to furniture,
            "Records" to box, "Throws" to Icons.Filled.Bed, "Wi-Fi router" to Icons.Filled.Cable,
        )),
        Things(TrialRoom("Main bedroom", "MAI", Green), listOf(
            "Bed frame" to Icons.Filled.Bed, "Mattress" to Icons.Filled.Bed, "Wardrobe" to Icons.Filled.Checkroom,
            "Duvet" to Icons.Filled.Bedtime, "Pillows" to Icons.Filled.Bedtime, "Bedside lamp" to Icons.Filled.Lightbulb,
            "Long mirror" to fragile, "Shoes" to Icons.Filled.ShoppingBag, "Winter coats" to Icons.Filled.Checkroom,
            "Jewellery box" to Icons.Filled.Star, "Chest of drawers" to furniture, "Alarm clock" to Icons.Filled.Schedule,
            "Big suitcase" to Icons.Filled.Luggage, "Laundry basket" to Icons.Filled.ShoppingBasket,
            "Hangers" to Icons.Filled.DryCleaning, "Spare bedding" to box, "Reading light" to Icons.Filled.Lightbulb,
        )),
        Things(TrialRoom("Kids' room", "KID", Yellow), listOf(
            "Bunk bed" to Icons.Filled.Bed, "Toy box" to Icons.Filled.Toys, "Lego" to Icons.Filled.Toys,
            "Picture books" to Icons.AutoMirrored.Filled.MenuBook, "Small desk" to Icons.Filled.TableChart,
            "Teddy bears" to Icons.Filled.Toys, "Art supplies" to box, "Night light" to Icons.Filled.Lightbulb,
            "School bags" to Icons.Filled.ShoppingBag, "Puzzles" to Icons.Filled.Toys, "Kids' clothes" to Icons.Filled.Checkroom,
            "Football" to Icons.Filled.FitnessCenter, "Bike helmet" to Icons.Filled.DirectionsCar,
            "Dolls' house" to fragile, "Train set" to Icons.Filled.Toys, "Beanbag" to Icons.Filled.Weekend,
            "Height chart" to Icons.Filled.Straighten,
        )),
        Things(TrialRoom("Office", "OFF", Orange), listOf(
            "Desk" to Icons.Filled.TableChart, "Office chair" to furniture, "Monitor" to Icons.Filled.Tv,
            "Printer" to Icons.Filled.Print, "Files" to Icons.Filled.Description, "Cables" to Icons.Filled.Cable,
            "Laptop" to Icons.Filled.Keyboard, "Keyboard" to Icons.Filled.Keyboard, "Shredder" to Icons.Filled.Archive,
            "Filing cabinet" to Icons.Filled.Archive, "Bookcase" to Icons.AutoMirrored.Filled.MenuBook,
            "Printer paper" to Icons.Filled.Description, "Old phones" to Icons.Filled.PhoneAndroid,
            "Stationery" to box, "Desk lamp" to Icons.Filled.Lightbulb, "Headphones" to Icons.Filled.Cable,
            "Passports" to Icons.Filled.Badge,
        )),
        Things(TrialRoom("Bathroom", "BAT", Purple), listOf(
            "Towels" to Icons.Filled.DryCleaning, "Bath mat" to Icons.Filled.Bathtub, "Toiletries" to Icons.Filled.WaterDrop,
            "First aid kit" to box, "Scales" to Icons.Filled.Straighten, "Shower caddy" to Icons.Filled.Bathtub,
            "Hair dryer" to Icons.Filled.Cable, "Medicines" to box, "Laundry" to Icons.Filled.ShoppingBasket,
            "Cleaning kit" to Icons.Filled.CleaningServices, "Mirror cabinet" to fragile, "Toothbrushes" to Icons.Filled.WaterDrop,
            "Bath toys" to Icons.Filled.Toys, "Spare loo rolls" to box, "Razor" to Icons.Filled.WaterDrop,
            "Dressing gowns" to Icons.Filled.Checkroom, "Plunger" to Icons.Filled.Handyman,
        )),
        Things(TrialRoom("Dining room", "DIN", Pink), listOf(
            "Dining table" to Icons.Filled.TableChart, "Six chairs" to furniture, "Sideboard" to furniture,
            "Good china" to fragile, "Candles" to Icons.Filled.LocalFireDepartment, "Tablecloths" to Icons.Filled.DryCleaning,
            "Serving bowls" to fragile, "Wine rack" to Icons.Filled.WineBar, "Tall vase" to fragile,
            "Placemats" to box, "Fruit bowl" to Icons.Filled.Restaurant, "Highchair" to furniture,
            "Napkins" to box, "Paintings" to Icons.Filled.PhotoLibrary, "Wall clock" to Icons.Filled.Schedule,
            "Glass cabinet" to fragile, "Dining rug" to Icons.Filled.Straighten,
        )),
        Things(TrialRoom("Hall", "HAL", Teal), listOf(
            "Coat rack" to Icons.Filled.Checkroom, "Shoe rack" to Icons.Filled.ShoppingBag, "Umbrellas" to Icons.Filled.WbSunny,
            "Spare keys" to Icons.Filled.Key, "Post tray" to Icons.Filled.Description, "Hall mirror" to fragile,
            "Doormat" to Icons.Filled.DoorFront, "Bike" to Icons.Filled.DirectionsCar, "Pram" to Icons.Filled.Toys,
            "Hats and gloves" to Icons.Filled.Checkroom, "Console table" to furniture, "Dog lead" to Icons.Filled.Key,
            "Torch" to Icons.Filled.Lightbulb, "Spare bulbs" to Icons.Filled.Lightbulb, "Meter readings" to Icons.Filled.Description,
            "Parcel shelf" to box, "Wellies" to Icons.Filled.WaterDrop,
        )),
        Things(TrialRoom("Garage", "GAR", Brown), listOf(
            "Toolbox" to Icons.Filled.Handyman, "Drill" to Icons.Filled.Build, "Lawnmower" to Icons.Filled.LocalFlorist,
            "Ladder" to Icons.Filled.Straighten, "Paint tins" to box, "Car seats" to Icons.Filled.DirectionsCar,
            "Camping gear" to Icons.Filled.Luggage, "Bikes" to Icons.Filled.DirectionsCar, "Workbench" to Icons.Filled.Handyman,
            "Garden chairs" to furniture, "Winter tyres" to Icons.Filled.DirectionsCar, "Jump leads" to Icons.Filled.Cable,
            "Fuel can" to Icons.Filled.LocalFireDepartment, "Screws and nails" to Icons.Filled.Build,
            "Pressure washer" to Icons.Filled.WaterDrop, "Fairy lights" to Icons.Filled.Lightbulb, "Recycling" to Icons.Filled.Warehouse,
        )),
        Things(TrialRoom("Loft", "LOF", Grey), listOf(
            "Christmas decorations" to Icons.Filled.Star, "Old photos" to Icons.Filled.PhotoLibrary, "Baby clothes" to Icons.Filled.Checkroom,
            "Empty suitcases" to Icons.Filled.Luggage, "Keepsakes" to Icons.Filled.Star, "Spare duvets" to Icons.Filled.Bedtime,
            "Old records" to box, "Spare lamps" to Icons.Filled.Lightbulb, "Toys to sell" to Icons.Filled.Toys,
            "School reports" to Icons.Filled.Description, "Camping stove" to Icons.Filled.LocalFireDepartment,
            "Desk fan" to Icons.Filled.Cable, "Old paperwork" to Icons.Filled.Archive, "Picture frames" to Icons.Filled.PhotoLibrary,
            "Dressing-up box" to Icons.Filled.Toys, "Spare tiles" to Icons.Filled.Roofing, "Sleeping bags" to Icons.Filled.Bedtime,
        )),
        Things(TrialRoom("Garden", "GDN", Lime), listOf(
            "Plant pots" to Icons.Filled.LocalFlorist, "Barbecue" to Icons.Filled.LocalFireDepartment,
            "Garden table" to Icons.Filled.TableChart, "Parasol" to Icons.Filled.WbSunny, "Hose" to Icons.Filled.WaterDrop,
            "Shed tools" to Icons.Filled.Handyman, "Bird feeder" to Icons.Filled.LocalFlorist, "Compost bin" to Icons.Filled.Warehouse,
            "Trampoline" to Icons.Filled.Toys, "Swing seat" to Icons.Filled.Weekend, "Herb pots" to Icons.Filled.LocalFlorist,
            "Lanterns" to Icons.Filled.Lightbulb, "Spring bulbs" to Icons.Filled.LocalFlorist, "Seed tins" to box,
            "Watering can" to Icons.Filled.WaterDrop, "Fire pit" to Icons.Filled.LocalFireDepartment, "Garden bench" to furniture,
        )),
        Things(TrialRoom("Spare room", "SPA", Navy), listOf(
            "Sofa bed" to Icons.Filled.Weekend, "Guest bedding" to Icons.Filled.Bedtime, "Ironing board" to Icons.Filled.Straighten,
            "Iron" to Icons.Filled.DryCleaning, "Sewing machine" to Icons.Filled.Build, "Exercise bike" to Icons.Filled.FitnessCenter,
            "Yoga mat" to Icons.Filled.FitnessCenter, "Dumbbells" to Icons.Filled.FitnessCenter, "Guest towels" to Icons.Filled.DryCleaning,
            "Bedside table" to furniture, "Card games" to Icons.Filled.Toys, "Fan heater" to Icons.Filled.LocalFireDepartment,
            "Clothes rail" to Icons.Filled.Checkroom, "Travel bags" to Icons.Filled.Luggage, "Folding chairs" to furniture,
            "Pile of books" to Icons.AutoMirrored.Filled.MenuBook, "Corner lamp" to Icons.Filled.Lightbulb,
        )),
    )
}

private val people = listOf("Sam", "Priya", "Grandad", "Alex", "Jo")

private val big = setOf(Icons.Filled.Chair, Icons.Filled.Weekend, Icons.Filled.Bed, Icons.Filled.TableChart)

/** Notes like the ones the lists show: where a thing is packed, who packed it, what to watch out for. */
private fun note(room: TrialRoom, icon: ImageVector, n: Int, i: Int): String {
    val box = "${room.code}-${n / 3 + 1}"
    return when {
        icon == Icons.Filled.WineBar && i % 2 == 0 -> "Fragile: wrap it well and keep it on top in the van"
        icon in big && i % 2 == 0 -> "Goes on the van as it is; needs two people"
        else -> when (i % 5) {
            0 -> "In box $box, packed by ${people[i % people.size]}"
            1 -> "Not packed yet"
            2 -> "In box $box with the other small things from the ${room.name.lowercase()}"
            3 -> "Packed by ${people[(i + 2) % people.size]} on Saturday"
            else -> "Ask before packing: it may be sold or given away"
        }
    }
}

/** The rows of the trial list, room by room. */
internal val TrialRows: List<TrialRow> by lazy {
    var i = 0
    houseContents.flatMap { group ->
        group.things.mapIndexed { n, (title, icon) ->
            TrialRow(i, title, icon, group.room, note(group.room, icon, n, i)).also { i++ }
        }
    }
}
