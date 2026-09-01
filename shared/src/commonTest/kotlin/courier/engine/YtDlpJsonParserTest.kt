package courier.engine

import courier.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpJsonParserTest {

    @Test
    fun testInstagramSinglePhotoClassification() {
        val json = """
        {
            "id": "BsOGulcndj-",
            "title": "Video by world_record_egg",
            "uploader": "Just An Egg \uD83E\uDD5A",
            "channel": "world_record_egg",
            "formats": [],
            "thumbnails": [
                {"url": "https://instagram.com/thumb1.jpg", "width": 150},
                {"url": "https://instagram.com/thumb_max.jpg", "width": 584}
            ]
        }
        """.trimIndent()

        val info = YtDlpJsonParser.parse(json, "https://www.instagram.com/p/BsOGulcndj-/")
        assertEquals(MediaType.IMAGE, info.mediaType)
        assertEquals("Photo by world_record_egg", info.title)
        assertEquals("https://instagram.com/thumb_max.jpg", info.thumbnailUrl)
        assertEquals(1, info.formats.size)
        assertEquals("photo", info.formats.first().formatId)
    }

    @Test
    fun testInstagramVideoCarouselClassification() {
        val json = """
        {
            "id": "BQ0eAlwhDrw",
            "title": "Post by instagram",
            "_type": "playlist",
            "entries": [
                {
                    "id": "BQ0dSaohpPW",
                    "title": "Video 1",
                    "ext": "mp4",
                    "vcodec": "avc1.4d401e",
                    "formats": [{"format_id": "1", "height": 720}]
                },
                {
                    "id": "BQ0dTpOhuHT",
                    "title": "Video 2",
                    "ext": "mp4",
                    "vcodec": "avc1.4d401e",
                    "formats": [{"format_id": "1", "height": 720}]
                },
                {
                    "id": "BQ0dT7RBFeF",
                    "title": "Video 3",
                    "ext": "mp4",
                    "vcodec": "avc1.4d401e",
                    "formats": [{"format_id": "1", "height": 720}]
                }
            ]
        }
        """.trimIndent()

        val info = YtDlpJsonParser.parse(json, "https://www.instagram.com/p/BQ0eAlwhDrw/")
        assertEquals(MediaType.GALLERY, info.mediaType)
        assertEquals(3, info.galleryEntries.size)
        assertTrue(info.galleryEntries.all { it.isVideo })
    }

    @Test
    fun testSyntheticPhotoCarouselClassification() {
        val json = """
        {
            "id": "photo_carousel_1",
            "title": "Video by photographer",
            "_type": "playlist",
            "entries": [
                {
                    "id": "slide_1",
                    "formats": [],
                    "thumbnails": [{"url": "https://instagram.com/slide1_hd.jpg"}]
                },
                {
                    "id": "slide_2",
                    "formats": [],
                    "thumbnails": [{"url": "https://instagram.com/slide2_hd.jpg"}]
                }
            ]
        }
        """.trimIndent()

        val info = YtDlpJsonParser.parse(json, "https://www.instagram.com/p/photo_carousel_1/")
        assertEquals(MediaType.GALLERY, info.mediaType)
        assertEquals("Post by photographer", info.title)
        assertEquals(2, info.galleryEntries.size)
        assertFalse(info.galleryEntries.any { it.isVideo })
    }

    @Test
    fun testStandardYouTubeVideoClassification() {
        val json = """
        {
            "id": "dQw4w9WgXcQ",
            "title": "Rick Astley - Never Gonna Give You Up",
            "uploader": "Rick Astley",
            "duration": 213,
            "formats": [
                {"format_id": "137", "height": 1080, "ext": "mp4"},
                {"format_id": "136", "height": 720, "ext": "mp4"}
            ],
            "thumbnails": [{"url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"}]
        }
        """.trimIndent()

        val info = YtDlpJsonParser.parse(json, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(MediaType.VIDEO, info.mediaType)
        assertEquals("Rick Astley - Never Gonna Give You Up", info.title)
        assertTrue(info.formats.any { it.resolution == "1080p" })
        assertTrue(info.formats.any { it.isAudioOnly })
    }

    @Test
    fun testDisplayThumbnailPrefersDisplaySizeOverLargest() {
        // The grid renders into ~105dp cells. Handing it the full-resolution
        // original meant decoding a 1080px+ image per cell, which is what made
        // the gallery picker stutter.
        val json = """
        {
            "id": "p1",
            "formats": [],
            "thumbnails": [
                {"url": "https://cdn/150.jpg", "width": 150},
                {"url": "https://cdn/640.jpg", "width": 640},
                {"url": "https://cdn/1080.jpg", "width": 1080},
                {"url": "https://cdn/full.jpg", "width": 1440}
            ]
        }
        """.trimIndent()

        val info = YtDlpJsonParser.parse(json, "https://www.instagram.com/p/p1/")
        assertEquals("https://cdn/640.jpg", info.thumbnailUrl)
        // The original is still recorded, just not used for display.
        assertEquals("https://cdn/full.jpg", info.directVideoUrl)
    }

    @Test
    fun testDisplayThumbnailParsesWidthFromInstagramUrl() {
        // Single Instagram photos carry no width field; the size is only in the
        // URL's sWxH segment.
        val json = """
        {
            "id": "p2",
            "formats": [],
            "thumbnails": [
                {"url": "https://cdn/n.jpg?stp=dst-jpg_e35_s150x150_tt6"},
                {"url": "https://cdn/n.jpg?stp=dst-jpg_e35_s480x480_tt6"},
                {"url": "https://cdn/n.jpg?stp=dst-jpg_e35_tt6"}
            ]
        }
        """.trimIndent()

        val info = YtDlpJsonParser.parse(json, "https://www.instagram.com/p/p2/")
        assertEquals("https://cdn/n.jpg?stp=dst-jpg_e35_s480x480_tt6", info.thumbnailUrl)
    }

    @Test
    fun testDisplayThumbnailFallsBackWhenAllCandidatesAreOversized() {
        val json = """
        {
            "id": "p3",
            "formats": [],
            "thumbnails": [
                {"url": "https://cdn/1080.jpg", "width": 1080},
                {"url": "https://cdn/2160.jpg", "width": 2160}
            ]
        }
        """.trimIndent()

        val info = YtDlpJsonParser.parse(json, "https://www.instagram.com/p/p3/")
        assertEquals("https://cdn/1080.jpg", info.thumbnailUrl, "Should take the smallest when nothing fits the budget")
    }

    @Test
    fun testMixedCarouselFlagsVideosAndPhotosSeparately() {
        // Instagram carousels mix the two. Each entry must be classified on its
        // own so the engine can run a photo pass and a video pass.
        val json = """
        {
            "id": "mixed_1",
            "title": "Video by creator",
            "_type": "playlist",
            "entries": [
                {"id": "a", "formats": [], "thumbnails": [{"url": "https://cdn/a.jpg", "width": 320}]},
                {"id": "b", "ext": "mp4", "vcodec": "avc1.4d401e", "formats": [{"format_id": "1", "height": 720}]},
                {"id": "c", "formats": [], "thumbnails": [{"url": "https://cdn/c.jpg", "width": 320}]}
            ]
        }
        """.trimIndent()

        val info = YtDlpJsonParser.parse(json, "https://www.instagram.com/p/mixed_1/")
        assertEquals(MediaType.GALLERY, info.mediaType)
        assertEquals(3, info.galleryEntries.size)
        assertFalse(info.galleryEntries[0].isVideo)
        assertTrue(info.galleryEntries[1].isVideo)
        assertFalse(info.galleryEntries[2].isVideo)
        // Indices are 1-based for --playlist-items
        assertEquals(listOf(2), info.galleryEntries.filter { it.isVideo }.map { it.index })
        assertEquals(listOf(1, 3), info.galleryEntries.filterNot { it.isVideo }.map { it.index })
    }

    @Test
    fun testLoginWalledVideoMustNotBeClassifiedAsPhoto() {
        // Critical regression test: A login-walled video has formats: [], but HAS duration: 137
        val json = """
        {
            "id": "C8YJEcXR3Zo",
            "title": "Reel by creator",
            "duration": 137,
            "formats": [],
            "thumbnails": [{"url": "https://instagram.com/thumb.jpg"}]
        }
        """.trimIndent()

        val info = YtDlpJsonParser.parse(json, "https://www.instagram.com/reel/C8YJEcXR3Zo/")
        assertEquals(MediaType.VIDEO, info.mediaType, "Login-walled video with duration must not be misclassified as an image")
    }
}
