package krtc.sfu

import krtc.sfu.room.VideoLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpstreamLossTest {
    @Test
    fun gapsAreRequestedRetriedAndForgotten() {
        val layer = VideoLayer(2)
        assertFalse(layer.trackArrival(65533, 0))
        assertTrue(layer.trackArrival(1, 0)) // 65534, 65535, 0 lost across the wrap
        assertEquals(listOf(65534, 65535, 0), layer.missingToRequest(0))
        assertEquals(emptyList(), layer.missingToRequest(50)) // not yet due for a retry

        layer.trackArrival(65535, 60) // arrived late / retransmitted
        assertFalse(layer.isMissing(65535))
        assertEquals(listOf(65534, 0), layer.missingToRequest(120))
        assertEquals(listOf(65534, 0), layer.missingToRequest(240))
        assertEquals(emptyList(), layer.missingToRequest(360)) // gave up after three requests
        assertFalse(layer.hasMissing())
    }

    @Test
    fun largeJumpsAreNotTreatedAsLoss() {
        val layer = VideoLayer(0)
        layer.trackArrival(100, 0)
        assertFalse(layer.trackArrival(5000, 0)) // stream restart, not 4899 lost packets
        assertFalse(layer.hasMissing())
    }
}
