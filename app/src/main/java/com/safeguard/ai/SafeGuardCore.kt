package com.safeguard.ai

import com.safeguard.ai.core.CarProperties
import com.safeguard.ai.core.LocationSource
import com.safeguard.ai.core.RoutePlace
import com.safeguard.ai.core.SignalHub
import com.safeguard.ai.core.VehicleGateway
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.HazardKind
import com.safeguard.ai.data.publicdata.HazardSource
import com.safeguard.ai.data.publicdata.Place
import com.safeguard.ai.data.publicdata.Places
import com.safeguard.ai.data.publicdata.RoadIceAdapter
import com.safeguard.ai.data.publicdata.TaasHotspotAdapter
import com.safeguard.ai.features.briefing.DepartureBriefing
import com.safeguard.ai.features.crash.CrashCard
import com.safeguard.ai.features.crash.CrashCare
import com.safeguard.ai.features.crash.DemoGuardianNotifier
import com.safeguard.ai.features.crash.GuardianStore
import com.safeguard.ai.features.crash.HazardStopCare
import com.safeguard.ai.features.disaster.DisasterBriefing
import com.safeguard.ai.features.disaster.DisasterMessage
import com.safeguard.ai.features.drowsy.DrowsyCabin
import com.safeguard.ai.features.drowsy.DrowsyCare
import com.safeguard.ai.features.hazard.CrowdEventKind
import com.safeguard.ai.features.hazard.HazardMap
import com.safeguard.ai.features.hazard.HazardRule
import com.safeguard.ai.features.hazard.SchoolZoneRoute
import com.safeguard.ai.features.logbook.PrefsTripLogStore
import com.safeguard.ai.features.logbook.TripLog
import com.safeguard.ai.features.maintenance.MaintenanceCare
import com.safeguard.ai.features.maintenance.PrefsMaintenanceStore
import com.safeguard.ai.features.maintenance.ServiceItem
import com.safeguard.ai.features.maintenance.ServiceStatus
import com.safeguard.ai.features.ontime.FlightPlan
import com.safeguard.ai.features.ontime.OnTimeCoach
import com.safeguard.ai.features.parking.ParkingBriefing
import com.safeguard.ai.features.score.PrefsScoreStore
import com.safeguard.ai.features.score.SafetyScore
import com.safeguard.ai.features.score.TripReport
import com.safeguard.ai.features.share.AdasEventDetector
import com.safeguard.ai.features.share.HazardReport
import com.safeguard.ai.features.share.HazardSharing
import com.safeguard.ai.features.share.HardBrakingDetector
import com.safeguard.ai.features.share.LocalOnlySharing
import com.safeguard.ai.features.share.SharingClient
import com.safeguard.ai.features.telltale.TelltaleCard
import com.safeguard.ai.features.telltale.TelltaleCatalog
import com.safeguard.ai.features.telltale.TelltaleGuide
import com.safeguard.ai.features.traffic.CongestionCabin
import com.safeguard.ai.features.traffic.CongestionCoach
import com.safeguard.ai.features.weather.CruiseCheck
import com.safeguard.ai.features.weather.SensorBlockage
import com.safeguard.ai.features.weather.VisibilityAssist
import com.safeguard.ai.features.weather.WeatherCoach
import com.safeguard.ai.features.weather.WeatherStatus
import com.safeguard.ai.integration.GleoReply
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.output.AmbientChannel
import com.safeguard.ai.output.Answer
import com.safeguard.ai.output.VoiceChannel
import com.safeguard.ai.output.VoiceConfirm
import com.safeguard.ai.output.WarningOutput
import com.safeguard.ai.risk.Arbiter
import com.safeguard.ai.risk.RiskEngine
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import com.safeguard.ai.scenario.ScenarioHooks
import com.safeguard.ai.scenario.ScenarioPlayer
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class UiState(
    val vehicle: VehicleGateway.State = VehicleGateway.State.CONNECTING,
    val snapshot: VehicleSnapshot = VehicleSnapshot(),
    val active: RiskEvent? = null,
    val weather: WeatherStatus = WeatherStatus(),
    /** Warning-light steps, shown only while parked. */
    val guide: TelltaleCard? = null,
    /** After a crash or an emergency stop: the location to read out to 119 and the guardian alert. */
    val crash: CrashCard? = null,
    /** The last trip's SafeGuard Score, shown while parked. */
    val trip: TripReport? = null,
    val maintenance: List<ServiceStatus> = emptyList(),
    /** Sharing server reachability; null when no server is configured or nothing was sent yet. */
    val serverOnline: Boolean? = null,
)

/** Wires signals → rules → arbiter → outputs. Lives as long as [SafeGuardService]. */
class SafeGuardCore(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val hub = SignalHub()
    val gateway = VehicleGateway(context, scope, hub::updateReal)
    val carProperties = CarProperties(context, hub::updateReal)
    val location = LocationSource(context, hub::updateReal, useGpsSpeed = { !carProperties.hasVehicleSpeed })
    private val voice = VoiceChannel(context)
    private val ambient = AmbientChannel(scope, gateway)
    private val confirm = VoiceConfirm(context)
    private val arbiter = Arbiter(scope, WarningOutput(voice, ambient, confirm))

    private val weatherCoach = WeatherCoach()
    private val visibilityAssist = VisibilityAssist(scope, gateway, weatherCoach.status, hub.snapshot)
    private val hazardMap = HazardMap()
    private val hazardRule = HazardRule(hazardMap, weather = { weatherCoach.status.value }, share = ::shareVehicleEvent)
    private val briefing = DepartureBriefing(hazardMap, weather = { weatherCoach.status.value })
    private val congestionCoach = CongestionCoach(reroute = location::requestReRoute)
    private val cruiseCheck = CruiseCheck(weather = { weatherCoach.status.value })
    private val schoolZoneRoute = SchoolZoneRoute(avoidSchoolZones = location::avoidSchoolZones)
    val maintenance = MaintenanceCare(PrefsMaintenanceStore(context))
    val settings = FeatureSettings(context)
    private val safetyScore = SafetyScore(
        PrefsScoreStore(context),
        recommendedKmh = { weatherCoach.status.value.takeIf { it.condition.isAdverse }?.recommendedKmh },
        // Parking at a rest stop to fight drowsiness is a break, not the end of the drive.
        quiet = { drowsyCare.stage > 0 },
        annualPremiumWon = { settings.annualPremiumWon },
    )
    val guardianStore = GuardianStore(context)

    /** The sharing server (round 2); null when no address is configured, and reports then stay on the car. */
    private val sharingClient: SharingClient? = BuildConfig.SAFEGUARD_SERVER.takeIf { it.isNotBlank() }
        ?.let { SharingClient(context, scope, it, hazardMap, hub.snapshot, guardianStore::get) }
    private val hazardSharing: HazardSharing = sharingClient ?: LocalOnlySharing()

    /** Automatic reports follow the sharing switch; a hazard the driver reports by voice is always sent. */
    private fun shareVehicleEvent(type: String, lat: Double, lon: Double, heading: Float?) {
        if (!settings.enabled(Feature.SHARING)) return
        share(HazardReport(type, lat, lon, heading, System.currentTimeMillis(), HazardReport.Source.VEHICLE))
    }

    private fun share(report: HazardReport) {
        hazardSharing.share(report)
        safetyScore.onShared(report.at)
    }

    private val hardBraking = HardBrakingDetector { lat, lon, heading -> shareVehicleEvent("HARD_BRAKING", lat, lon, heading) }

    @Volatile private var restAreas: List<Place> = emptyList()
    @Volatile private var serviceCenters: List<Place> = emptyList()
    private val drowsyCabin: DrowsyCabin = DrowsyCabin(scope, gateway, offer = { engine.inject(it) }, onRested = { drowsyCare.onRested() })
    private val drowsyCare: DrowsyCare = DrowsyCare(
        restAreas = { restAreas },
        awaken = { drowsyCabin.awaken() },
        addWaypoint = { location.addWaypoint("${it.name} 졸음쉼터", it.latitude, it.longitude) },
        nap = { drowsyCabin.nap() },
    )
    private val guardian = sharingClient?.guardian ?: DemoGuardianNotifier(guardianStore::get)
    private val crashCare: CrashCare = CrashCare(
        scope,
        say = { engine.inject(it) },
        address = { location.currentAddress() },
        landmarks = { restAreas },
        guardian = guardian,
        onCrash = { s ->
            // The 119 location must be on screen even if navigation or media was in front.
            bringToFront()
            s.latitude?.let { lat -> s.longitude?.let { lon -> shareVehicleEvent("CRASH", lat, lon, s.headingDeg) } }
        },
    )

    /** Hazard lights on a fast road without an airbag: the gap the OEM airbag services leave. */
    private val hazardStopCare = HazardStopCare(
        scope,
        address = { location.currentAddress() },
        landmarks = { restAreas },
        guardian = guardian,
        crashActive = { crashCare.active },
    )

    /** The 시군구 of the navigation's current address ("경남 김해시"); a scenario may set its own. */
    @Volatile private var naviRegion: String? = null
    @Volatile private var scenarioRegion: String? = null
    @Volatile private var scenarioAhead: List<RoutePlace>? = null
    @Volatile private var scenarioLogbook = false
    @Volatile private var scenarioFlight: FlightPlan? = null
    private val disasterBriefing = DisasterBriefing(
        region = { scenarioRegion ?: naviRegion },
        ahead = { scenarioAhead ?: location.routeAhead },
        weather = { weatherCoach.status.value },
        crowdRain = ::crowdHeavyRain,
    )

    /** Driver-assist lights turning yellow in rain, snow or fog: the sensors are covered. */
    private val sensorBlockage = SensorBlockage(weather = { weatherCoach.status.value })

    /** Collision warnings and lost lane lines, reported anonymously for the Safety Mesh. */
    private val adasEvents = AdasEventDetector(weather = { weatherCoach.status.value.condition }, report = ::shareVehicleEvent)

    private val parkingBriefing = ParkingBriefing(alerts = { disasterBriefing.kindsHere() })

    /** The drive to a flight or a train; a scenario's route stands in for the emulator's navigation. */
    private val onTimeCoach = OnTimeCoach(
        destination = { scenarioAhead?.maxByOrNull { it.remainSeconds } ?: location.destination },
        say = { engine.inject(it) },
        // A demo's recorded flight stands in for the live airport data, so rehearsals don't depend on today's flights.
        lookup = { q -> scenarioFlight ?: sharingClient?.flight(q) },
        scope = scope,
    )

    val tripLog = TripLog(
        scope,
        PrefsTripLogStore(context),
        enabled = { settings.enabled(Feature.LOGBOOK) || scenarioLogbook },
        address = { location.currentAddress()?.address },
    )

    /** Other cars' shared heavy-rain reports near this car (the server confirms each from two cars). */
    private fun crowdHeavyRain(): Boolean {
        val s = hub.snapshot.value
        val lat = s.latitude ?: return false
        val lon = s.longitude ?: return false
        return hazardMap.active().any {
            it.source == HazardSource.SHARED && it.name == "HEAVY_RAIN" &&
                Geo.distanceMeters(lat, lon, it.latitude, it.longitude) <= CROWD_RAIN_M
        }
    }

    /** Set by the service: shows the SafeGuard screen above whatever app is in front. */
    @Volatile var bringToFront: () -> Unit = {}
    private val telltaleGuide = TelltaleGuide(
        serviceCenters = { serviceCenters },
        routeTo = { location.routeTo(it.name, it.latitude, it.longitude) },
    )

    /**
     * One rule per feature; adding a feature means adding its rule here.
     * The weather coach runs first so the hazard rule, briefing and cruise check see the current road condition.
     */
    private val rules: List<RiskRule> = listOf(crashCare, hazardStopCare) +
        listOf(
            optional(Feature.WEATHER, weatherCoach),
            // A covered sensor is never muted: the driver may be steering on an assist that is gone.
            sensorBlockage,
            adasEvents,
            // Only the statistical hotspots follow the switch: hazards other cars confirmed are always said.
            optional(Feature.HOTSPOT, hazardRule) { it.level == RiskLevel.HOTSPOT },
            optional(Feature.BRIEFING, briefing),
            optional(Feature.TRAFFIC, congestionCoach),
            cruiseCheck,
            optional(Feature.SCHOOL_ROUTE, schoolZoneRoute),
            optional(Feature.DROWSY, drowsyCare),
            optional(Feature.TELLTALE, telltaleGuide),
            optional(Feature.MAINTENANCE, maintenance),
            hardBraking,
            optional(Feature.SCORE, safetyScore),
            optional(Feature.DISASTER, disasterBriefing),
            optional(Feature.PARKING, parkingBriefing),
            optional(Feature.ONTIME, onTimeCoach),
            tripLog,
        ).map(::mutedAfterCrash)

    private fun optional(feature: Feature, rule: RiskRule, mutable: (RiskEvent) -> Boolean = { true }) =
        switchable(rule, on = { settings.enabled(feature) }, mutable = mutable)

    private val engine: RiskEngine = RiskEngine(scope, hub.snapshot, arbiter, rules)
    private val congestionCabin = CongestionCabin(scope, gateway, hub.snapshot, keepOutsideAir = { visibilityAssist.engaged || drowsyCabin.awakening },
        offer = { if (settings.enabled(Feature.TRAFFIC)) engine.inject(it) })
    val scenarios = ScenarioPlayer(context, scope, hub, object : ScenarioHooks {
        override fun injectRisk(event: RiskEvent) = engine.inject(event)
        override fun crowdEvent(kind: CrowdEventKind, lat: Double, lon: Double) = hazardMap.report(kind, lat, lon)
        override fun restartBriefing() = briefing.restart(System.currentTimeMillis())
        override fun answer(answer: Answer) = confirm.inject(answer)
        override fun offerRelax() = congestionCabin.offerRelaxNow()
        override fun escalateDrowsy() = drowsyCare.escalateNow()
        override fun arriveAtWaypoint(name: String) = drowsyCare.onArrivedAtRestStop()
        override fun reply(text: String?) = confirm.injectReply(text)
        override fun region(name: String?) {
            scenarioRegion = name?.let(DisasterBriefing::regionOf)
        }
        override fun disaster(message: DisasterMessage) = disasterBriefing.offer(listOf(message))
        override fun routeAhead(places: List<RoutePlace>?) {
            scenarioAhead = places
        }
        override fun logbook(on: Boolean) {
            scenarioLogbook = on
        }
        override fun flight(plan: FlightPlan?) {
            scenarioFlight = plan
        }
        override fun onReset() {
            scenarioRegion = null
            scenarioAhead = null
            scenarioLogbook = false
            scenarioFlight = null
            crashCare.reset()
            hazardStopCare.reset()
            safetyScore.minTripMs = SafetyScore.MIN_TRIP_MS
            // A scripted car never slows for a warning: scoring it would only read out a meaningless score.
            safetyScore.discardTrip()
            // Also runs when a scenario starts: rehearsals of the same demo must play identically.
            weatherCoach.reset()
            sensorBlockage.reset()
            parkingBriefing.reset()
            onTimeCoach.reset()
            arbiter.clearCooldowns()
        }
        override fun napSeconds(seconds: Int) {
            drowsyCabin.napMs = seconds * 1_000L
        }
        override fun scoreMinTripSeconds(seconds: Int) {
            safetyScore.minTripMs = seconds * 1_000L
        }
        override fun serviceRecord(item: String, km: Double) {
            ServiceItem.entries.firstOrNull { it.name == item }?.let { maintenance.recordServiceAt(it, km) }
        }
    })

    val ui: StateFlow<UiState> =
        combine(gateway.state, hub.snapshot, arbiter.active, weatherCoach.status, telltaleGuide.card) { v, s, a, w, g ->
            UiState(v, s, a, w, g)
        }.combine(crashCare.card.combine(hazardStopCare.card) { crash, stop -> crash ?: stop }) { state, crash ->
            state.copy(crash = crash)
        }
            .combine(safetyScore.report) { state, trip -> state.copy(trip = trip) }
            .combine(maintenance.status) { state, items -> state.copy(maintenance = items) }
            .combine(sharingClient?.online ?: MutableStateFlow(null)) { state, online -> state.copy(serverOnline = online) }
            .stateIn(scope, SharingStarted.Eagerly, UiState())

    /** "현대 2024년식" from the VIN, for the settings screen; null until the car answers. */
    @Volatile var vehicleLabel: String? = null
        private set

    fun start() {
        scope.launch(Dispatchers.IO) {
            hazardMap.load(publicDataAdapters())
            restAreas = Places.load(context, "public/rest_areas.json")
            serviceCenters = Places.load(context, "public/service_centers.json")
        }
        location.onWaypointArrived = { drowsyCare.onArrivedAtRestStop() }
        sharingClient?.onDisasterMessages = disasterBriefing::offer
        sharingClient?.start()
        // The disaster SMS are addressed by 시군구; the navigation's address is cheap to ask for every few minutes.
        scope.launch {
            while (isActive) {
                if (hub.snapshot.value.latitude != null) {
                    location.currentAddress()?.address?.let(DisasterBriefing::regionOf)?.let { naviRegion = it }
                }
                delay(REGION_REFRESH_MS)
            }
        }
        voice.start()
        confirm.start()
        gateway.start()
        carProperties.start()
        location.start()
        arbiter.start()
        engine.start()
        visibilityAssist.start()
        congestionCabin.start()
        // A flight being driven to is checked again every few minutes (the coach decides when).
        scope.launch {
            while (isActive) {
                delay(FLIGHT_REFRESH_TICK_MS)
                onTimeCoach.refresh(System.currentTimeMillis())
            }
        }
        // The score checks whether the driver slows down after each warning they actually heard.
        scope.launch {
            arbiter.active.collect { event -> event?.let { safetyScore.onWarning(it, hub.snapshot.value, System.currentTimeMillis()) } }
        }
        scope.launch {
            gateway.state.first { it == VehicleGateway.State.READY }
            vehicleLabel = gateway.vin()?.let { MaintenanceCare.describeVin(it) }
        }
        // Model-level warning-light statistics: each newly lit catalogued light, once, when the model is known.
        sharingClient?.let { client ->
            scope.launch {
                var lit = emptySet<String>()
                hub.snapshot.collect { s ->
                    val now = s.telltales.keys.filter { it in TelltaleCatalog.entries }.toSet()
                    val model = vehicleLabel
                    if (model != null) (now - lit).forEach { client.reportTelltale(model, it) }
                    lit = now
                }
            }
        }
    }

    fun stop() {
        scenarios.stop()
        voice.stop()
        confirm.stop()
        location.stop()
        carProperties.stop()
        gateway.stop()
        scope.cancel()
    }

    /**
     * Gleo voice commands (`assets/schema.json`). The app answers in its own voice through the arbiter, so a
     * spoken command waits its turn behind a real warning; Gleo gets a short English result.
     */
    fun handleVoiceCommand(action: String, params: Map<String, String>): GleoReply {
        val now = System.currentTimeMillis()
        val s = hub.snapshot.value
        return when (action) {
            "WAKE_ME_UP" -> {
                drowsyCare.driverSaysSleepy()
                GleoReply(action, GleoReply.Status.SUCCESS, "Started the wake-up routine and will offer the next rest area.")
            }
            "REPORT_HAZARD" -> reportHazard(action, params["hazardType"] ?: "OTHER", s, now)
            "SAFETY_BRIEFING" -> {
                val lights = s.telltales.keys.mapNotNull { TelltaleCatalog.entries[it]?.name }
                engine.inject(briefing.onDemand(s, now, lights))
                GleoReply(action, GleoReply.Status.SUCCESS, "Read out the safety briefing.")
            }
            else -> GleoReply(action, GleoReply.Status.FAIL, "SafeGuard does not support this command.", silent = false)
        }
    }

    private fun reportHazard(action: String, type: String, s: VehicleSnapshot, now: Long): GleoReply {
        val lat = s.latitude
        val lon = s.longitude
        if (lat == null || lon == null) {
            engine.inject(RiskEvent("report-no-location-$now", RiskLevel.INFO, "위치를 아직 몰라서 제보하지 못했어요.", "위험 제보 실패", cooldownMs = 0))
            return GleoReply(action, GleoReply.Status.FAIL, "The car's position is not known yet.")
        }
        // "앞에 낙하물 있어": the driver sees it ahead, so place it a short way along the direction of travel.
        val (hLat, hLon) = s.headingDeg?.let { Geo.offset(lat, lon, it.toDouble(), REPORT_AHEAD_M) } ?: (lat to lon)
        share(HazardReport(type, hLat, hLon, s.headingDeg, now, HazardReport.Source.VOICE))
        val label = HAZARD_LABELS[type] ?: "위험"
        engine.inject(
            RiskEvent(
                key = "report-$now",
                level = RiskLevel.INFO,
                speech = "알려 주셔서 고마워요. 뒤에 오는 차들에게 앞의 $label 소식을 전할게요.",
                screenText = "위험 제보 · $label",
                cooldownMs = 0,
            )
        )
        return GleoReply(action, GleoReply.Status.SUCCESS, "Reported the hazard to cars behind.")
    }

    /**
     * After a crash, or during an emergency stop, only the care speaks; a weather tip, a jam briefing or the
     * trip score would be noise while people should be getting out of the car.
     */
    private fun mutedAfterCrash(rule: RiskRule) = RiskRule { s, now ->
        if (crashCare.active || hazardStopCare.active) emptyList() else rule.evaluate(s, now)
    }

    private companion object {
        const val REPORT_AHEAD_M = 150.0
        const val REGION_REFRESH_MS = 3 * 60_000L
        const val CROWD_RAIN_M = 10_000.0
        const val FLIGHT_REFRESH_TICK_MS = 60_000L
        val HAZARD_LABELS = mapOf(
            "DEBRIS" to "낙하물", "ACCIDENT" to "사고", "ICE" to "빙판", "POTHOLE" to "포트홀",
            "STOPPED_VEHICLE" to "멈춘 차", "OTHER" to "위험",
        )
    }

    /** Stage-1 submission: bundled static seeds (no live API calls, no keys in the app). */
    private fun publicDataAdapters() = listOf(
        TaasHotspotAdapter(context, "public/taas_child_schoolzone.json", HazardKind.CHILD_HOTSPOT, HazardSource.TAAS_CHILD),
        TaasHotspotAdapter(context, "public/taas_child_pedestrian.json", HazardKind.CHILD_HOTSPOT, HazardSource.TAAS_CHILD),
        TaasHotspotAdapter(context, "public/taas_holiday.json", HazardKind.HOLIDAY_HOTSPOT, HazardSource.TAAS_HOLIDAY),
        RoadIceAdapter(context, "public/road_ice.json"),
    )
}
