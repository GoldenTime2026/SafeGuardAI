package com.safeguard.ai.data.publicdata

/** What kind of risk a point represents; decides when it is active and how it is announced. */
enum class HazardKind {
    /** Child traffic-accident hotspot (TAAS school-zone / pedestrian-child datasets): active at school hours. */
    CHILD_HOTSPOT,
    /** Holiday traffic-accident hotspot (TAAS holiday dataset): active during Seollal / Chuseok. */
    HOLIDAY_HOTSPOT,
    /** Possible black ice (KMA road-hazard info or KEC freezing section): active when freezing. */
    ICE_RISK,
    /** Several cars actually slipped / saw the icy telltale here recently. */
    CROWD_SLIP,
    /** A car stopped with hazard lights here (digital warning triangle). */
    CROWD_HAZARD_LIGHTS,
    /** Received from the sharing server: what other cars found or their drivers reported (type in [HazardPoint.name]). */
    SHARED,
}

enum class HazardSource(val label: String) {
    TAAS_CHILD("한국도로교통공단 어린이 사고다발지역"),
    TAAS_HOLIDAY("한국도로교통공단 연휴 사고다발지역"),
    KMA_ROAD_ICE("기상청 도로 살얼음 정보"),
    KEC_ICE_SECTION("한국도로공사 결빙구간"),
    CROWD("세이프가드 차량 신호"),
    SHARED("세이프가드 공유 서버"),
    SCENARIO("시연 시나리오"),
}

/**
 * One place worth warning about. Public points come from bundled seed data; crowd points are
 * created by [com.safeguard.ai.features.hazard.HazardMap] from vehicle events.
 */
data class HazardPoint(
    val id: String,
    val kind: HazardKind,
    val source: HazardSource,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    /** Short place name for speech, e.g. "○○초등학교 앞". */
    val name: String,
    /** Number of independent confirmations (crowd events, matching public data). */
    val confirmations: Int = 1,
    val updatedAt: Long = 0L,
    /** Crowd points expire; public points don't. */
    val expiresAt: Long? = null,
    /** True for clearly-labelled demo seeds that are not real public data. */
    val demo: Boolean = false,
    /** Direction of travel of the cars that reported it; only cars going the same way are warned. */
    val headingDeg: Float? = null,
)

/** One public-data source. Each adapter turns its own seed format into [HazardPoint]s. */
interface PublicDataAdapter {
    val name: String
    fun load(): List<HazardPoint>
}
