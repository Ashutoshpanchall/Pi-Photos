package com.example.piphotos

/**
 * Debug-only MediaSource containing hermetic demo data.
 * Physically isolated in src/debug so that release APKs contain 0 demo items.
 */
class DemoMediaSource : MediaSource {

    private val mediaList = mutableListOf(
        MediaItem("1", "Cafe Morning Table", "Today · 8:15 AM", "Recents", R.drawable.bg_photo_1, isFavorite = true),
        MediaItem("2", "Cat on Windowsill", "Today · 9:30 AM", "Recents", R.drawable.bg_photo_2, isFavorite = true),
        MediaItem("3", "Mountain Lake Reflection", "Today · 10:42 AM", "Recents", R.drawable.bg_photo_3, isVideo = true, duration = "0:42", isFavorite = true),
        MediaItem("4", "Apartment Facade & Bike", "Today · 11:20 AM", "Recents", R.drawable.bg_photo_4),
        MediaItem("5", "Figs & Cheese Platter", "Today · 12:45 PM", "Recents", R.drawable.bg_photo_5, isFavorite = true),
        MediaItem("6", "Golden Coastal Sunset", "Today · 6:30 PM", "Recents", R.drawable.bg_photo_6, isFavorite = true),
        MediaItem("7", "Monstera Leaf Droplets", "Today · 7:15 PM", "Recents", R.drawable.bg_photo_7),
        MediaItem("8", "Study Desk with Journal", "Today · 9:00 PM", "Recents", R.drawable.bg_photo_8),

        MediaItem("9", "Bookshelf with Glass Orb", "Yesterday · 10:15 AM", "Recents", R.drawable.bg_photo_3),
        MediaItem("10", "Aerial Misty Forest Road", "Yesterday · 11:45 AM", "Recents", R.drawable.bg_photo_2, isVideo = true, duration = "1:15"),
        MediaItem("11", "Artisan Coffee Latte Pour", "Yesterday · 1:20 PM", "Recents", R.drawable.bg_photo_1, isFavorite = true),
        MediaItem("12", "Vintage Blue Scooter", "Yesterday · 3:00 PM", "Recents", R.drawable.bg_photo_4),
        MediaItem("13", "Heirloom Tomatoes Market", "Yesterday · 4:10 PM", "Recents", R.drawable.bg_photo_6),
        MediaItem("14", "Mountain Summit Hiker", "Yesterday · 5:50 PM", "Recents", R.drawable.bg_photo_5),

        MediaItem("15", "Sunset Harbor Marina", "June 12, 2026", "Recents", R.drawable.bg_photo_4),
        MediaItem("16", "Sunflower Center Macro", "June 12, 2026", "Recents", R.drawable.bg_photo_6),
        MediaItem("17", "Futuristic Atrium Design", "June 12, 2026", "Recents", R.drawable.bg_photo_1, isVideo = true, duration = "0:28"),
        MediaItem("18", "Fresh Sourdough Loaf", "June 12, 2026", "Recents", R.drawable.bg_photo_5),
        MediaItem("19", "Camper Van on Coast Cliff", "June 12, 2026", "Recents", R.drawable.bg_photo_2),
        MediaItem("20", "Night City Highway Trails", "June 12, 2026", "Recents", R.drawable.bg_photo_3, isVideo = true, duration = "2:04"),
        MediaItem("21", "Nordic Cabin Interior", "June 10, 2026", "Recents", R.drawable.bg_photo_7),
        MediaItem("22", "Mountain Ridge Trail", "June 10, 2026", "Recents", R.drawable.bg_photo_8),
        MediaItem("23", "Quiet Seaside Dock", "June 08, 2026", "Recents", R.drawable.bg_photo_4),
        MediaItem("24", "Morning Garden Dew", "June 08, 2026", "Recents", R.drawable.bg_photo_5),

        MediaItem("k1", "Kyoto Shrine Gate", "May 2026", "Summer in Kyoto", R.drawable.bg_photo_7, isFavorite = true),
        MediaItem("k2", "Bamboo Grove Path", "May 2026", "Summer in Kyoto", R.drawable.bg_photo_5),
        MediaItem("k3", "Kiyomizu Temple Overlook", "May 2026", "Summer in Kyoto", R.drawable.bg_photo_2),
        MediaItem("k4", "Tea House Garden", "May 2026", "Summer in Kyoto", R.drawable.bg_photo_1),
        MediaItem("k5", "Stone Lantern Covered in Moss", "May 2026", "Summer in Kyoto", R.drawable.bg_photo_8),
        MediaItem("k6", "Traditional Machiya Courtyard", "May 2026", "Summer in Kyoto", R.drawable.bg_photo_3),
        MediaItem("k7", "Evening Gion Street", "May 2026", "Summer in Kyoto", R.drawable.bg_photo_6),
        MediaItem("k8", "Pagoda Sunset View", "May 2026", "Summer in Kyoto", R.drawable.bg_photo_4),

        MediaItem("f1", "Sunday Family Breakfast", "April 2026", "Family & Home", R.drawable.bg_photo_1),
        MediaItem("f2", "Backyard Garden Blooms", "April 2026", "Family & Home", R.drawable.bg_photo_5),
        MediaItem("f3", "Living Room Sunshine", "April 2026", "Family & Home", R.drawable.bg_photo_2),
        MediaItem("f4", "Homemade Pasta Dinner", "April 2026", "Family & Home", R.drawable.bg_photo_4),
        MediaItem("f5", "Cat Napping on Rug", "April 2026", "Family & Home", R.drawable.bg_photo_8),
        MediaItem("f6", "Bookshelf Corner", "April 2026", "Family & Home", R.drawable.bg_photo_3),

        MediaItem("p1", "Big Sur Ocean Bluff", "March 2026", "Pacific Coast", R.drawable.bg_photo_8, isFavorite = true),
        MediaItem("p2", "Crashing Wave Whitewater", "March 2026", "Pacific Coast", R.drawable.bg_photo_2),
        MediaItem("p3", "Sunset Over Lighthouse", "March 2026", "Pacific Coast", R.drawable.bg_photo_4),
        MediaItem("p4", "Coastal Highway Curve", "March 2026", "Pacific Coast", R.drawable.bg_photo_6),
        MediaItem("p5", "Tide Pools and Sea Stars", "March 2026", "Pacific Coast", R.drawable.bg_photo_5),

        MediaItem("w1", "Alpine Meadow Wildflowers", "February 2026", "Weekend Hikes", R.drawable.bg_photo_2),
        MediaItem("w2", "Pine Ridge Trailhead", "February 2026", "Weekend Hikes", R.drawable.bg_photo_5),
        MediaItem("w3", "Summit Panorama Horizon", "February 2026", "Weekend Hikes", R.drawable.bg_photo_7),
        MediaItem("w4", "Mountain Creek Crossing", "February 2026", "Weekend Hikes", R.drawable.bg_photo_3),

        MediaItem("t1", "Blurry Street Snapshot", "Deleted 3 days ago · 8.2 MB", "Recents", R.drawable.bg_photo_4, isTrash = true),
        MediaItem("t2", "Accidental Pocket Photo", "Deleted 12 days ago · 4.1 MB", "Recents", R.drawable.bg_photo_8, isTrash = true)
    )

    private val customAlbums = mutableListOf("Summer in Kyoto", "Family & Home", "Pacific Coast", "Weekend Hikes")

    override fun getMediaForCategory(category: String): List<MediaItem> {
        return when (category.lowercase()) {
            "recents" -> mediaList.filter { !it.isTrash && !it.isVault && it.category == "Recents" }
            "favorites" -> mediaList.filter { !it.isTrash && !it.isVault && it.isFavorite }
            "videos" -> mediaList.filter { !it.isTrash && !it.isVault && it.isVideo }
            "trash" -> mediaList.filter { it.isTrash }
            "vault" -> emptyList()
            else -> mediaList.filter { !it.isTrash && !it.isVault && it.category.equals(category, ignoreCase = true) }
        }
    }

    override fun getFolders(): List<FolderItem> {
        return customAlbums.mapIndexed { idx, name ->
            val items = getMediaForCategory(name)
            FolderItem(
                bucketId = "album_$idx",
                name = name,
                coverDrawableRes = items.firstOrNull()?.drawableRes ?: R.drawable.bg_photo_1,
                itemCount = items.size
            )
        }
    }

    override fun getCustomAlbums(): List<String> = customAlbums.toList()

    override fun getItem(id: String): MediaItem? = mediaList.find { it.id == id }

    override fun addCustomAlbum(name: String) {
        if (!customAlbums.contains(name)) customAlbums.add(name)
    }

    override fun renameAlbum(oldName: String, newName: String) {
        val index = customAlbums.indexOf(oldName)
        if (index != -1) {
            customAlbums[index] = newName
            mediaList.filter { it.category == oldName }.forEach { it.category = newName }
        }
    }

    override fun deleteAlbum(name: String, preserveMedia: Boolean) {
        customAlbums.remove(name)
        if (preserveMedia) {
            mediaList.filter { it.category == name }.forEach { it.category = "Recents" }
        } else {
            mediaList.filter { it.category == name }.forEach { it.isTrash = true }
        }
    }
}

