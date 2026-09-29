package com.olivier.gpxroad.shared.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Lecteur GPX partagé : mêmes règles que `GPXParser.swift` (iOS). */
class GpxParserTest {
    @Test
    fun readsTrackPointsNameAndMetadataDate() {
        val doc = GpxParser.parse(
            """<?xml version="1.0" encoding="UTF-8"?>
            <!-- export <trkpt> dans un commentaire, ignoré -->
            <gpx version="1.1" creator="GPXroad" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>Vosges &amp; Alsace</name><time>2026-09-28T08:31:00Z</time></metadata>
              <wpt lat="47.1" lon="7.1"><name>Col</name><time>2026-01-01T00:00:00Z</time></wpt>
              <trk><name>Autre nom</name><trkseg>
                <trkpt lat="47.5" lon="7.3"><ele>312.5</ele><time>2026-09-28T08:31:05Z</time></trkpt>
                <trkpt lon='7.31' lat='47.51'/>
                <trkpt lat="47.52" lon="7.32"><ele>abc</ele></trkpt>
              </trkseg></trk>
              <rte><rtept lat="1" lon="1"/></rte>
            </gpx>""",
        )
        assertEquals("Vosges & Alsace", doc.name)
        assertEquals("2026-09-28T08:31:00Z", doc.metadataTimeIso)
        assertEquals(3, doc.points.size, "les points de route ne comptent pas s'il y a une trace")
        assertEquals(GpxPoint(47.5, 7.3, 312.5, "2026-09-28T08:31:05Z"), doc.points[0])
        assertEquals(GpxPoint(47.51, 7.31), doc.points[1])
        assertNull(doc.points[2].elevation)
        assertEquals(listOf(GpxPoint(47.1, 7.1, null, "2026-01-01T00:00:00Z")), doc.waypoints)
    }

    @Test
    fun routeIsUsedWhenThereIsNoTrack() {
        val doc = GpxParser.parse("""<gpx><rte><name><![CDATA[Boucle <nord>]]></name><rtept lat="1.5" lon="2.5"/><rtept lat="1.6" lon="2.6"></rtept></rte></gpx>""")
        assertEquals("Boucle <nord>", doc.name)
        assertEquals(listOf(GpxPoint(1.5, 2.5), GpxPoint(1.6, 2.6)), doc.points)
        assertNull(doc.metadataTimeIso)
    }

    @Test
    fun namespacePrefixesAndEntitiesAreHandled() {
        val doc = GpxParser.parse("""<g:gpx xmlns:g="x"><g:trk><g:name>Caf&#233; &#x2192; col</g:name><g:trkseg><g:trkpt g:lat="10" g:lon="20"/></g:trkseg></g:trk></g:gpx>""")
        assertEquals("Café → col", doc.name)
        assertEquals(1, doc.points.size)
    }

    @Test
    fun invalidFilesAreRejected() {
        assertEquals(GpxParseException.Reason.INVALID_XML, assertFailsWith<GpxParseException> { GpxParser.parse("pas un gpx") }.reason)
        assertEquals(GpxParseException.Reason.INVALID_XML, assertFailsWith<GpxParseException> { GpxParser.parse("<html><body/></html>") }.reason)
        assertEquals(GpxParseException.Reason.INVALID_XML, assertFailsWith<GpxParseException> { GpxParser.parse("<gpx><trk><trkpt lat=\"1\" lon=\"2\"") }.reason)
        assertEquals(GpxParseException.Reason.NO_TRACK_DATA, assertFailsWith<GpxParseException> { GpxParser.parse("<gpx><trk/></gpx>") }.reason)
        assertEquals(GpxParseException.Reason.NO_TRACK_DATA, assertFailsWith<GpxParseException> { GpxParser.parse("<gpx><trk><trkseg><trkpt lat=\"91\" lon=\"2\"/></trkseg></trk></gpx>") }.reason)
    }

    @Test
    fun orderAndTraversalKeyMatchIOS() {
        val points = listOf(GpxPoint(47.0, 7.0), GpxPoint(47.000001, -7.25), GpxPoint(48.0, 8.0))
        assertEquals(points.reversed(), TrackOrder.reordered(points, reversed = true))
        assertEquals(listOf(points[1], points[2], points[0]), TrackOrder.reordered(points, reversed = false, startIndex = 1))
        assertEquals(points, TrackOrder.reordered(points, reversed = false, startIndex = 7))
        // iOS : "\(id)|%.6f,%.6f;%.6f,%.6f"
        assertEquals("ID|47.000000,7.000000;47.000001,-7.250000", TrackOrder.traversalKey("ID", points))
    }
}
