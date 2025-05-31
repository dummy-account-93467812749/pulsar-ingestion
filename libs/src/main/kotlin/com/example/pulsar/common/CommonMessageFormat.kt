package com.example.pulsar.common

enum class SourceType {
    Geotab, CalAmp, Gm, Toyota, Ford, DaimlerPro, FleetComplete, Volkswagen, Tesla, Bmw, Azuga, Mb, PlatformScience, Samsara, Stellantis, Rivian, Isaac, Motive, Hyundai, OmnitracsOT1
}

enum class IgnitionStatus { ON, OFF, UNKNOWN }
enum class DevicePowerSource { BATTERY, EXTERNAL, UNKNOWN }
enum class MilStatus { ON, OFF, UNKNOWN }
enum class CommonCollisionStatus { DETECTED, NOT_DETECTED, UNKNOWN }
enum class GearPosition { PARK, REVERSE, NEUTRAL, DRIVE, LOW, UNKNOWN }
enum class SunroofStatus { OPEN, CLOSED, AJAR, UNKNOWN }
enum class EVPlugStatus { PLUGGED_IN, NOT_PLUGGED_IN, UNKNOWN }
enum class EvChargingState { CHARGING, NOT_CHARGING, COMPLETE, FAULT, UNKNOWN }
enum class EvChargeModeTypes { AC_LEVEL_1, AC_LEVEL_2, DC_FAST_CHARGE, UNKNOWN }
enum class SeatbeltStatus { FASTENED, NOT_FASTENED, UNKNOWN }
enum class CellRssiStatus { EXCELLENT, GOOD, FAIR, POOR, UNKNOWN }
enum class Light { ON, OFF, UNKNOWN }
enum class DieselExhaustFluidStatus { NORMAL, LOW, CRITICALLY_LOW, UNKNOWN }
enum class DieselExhaustFilterStatus { NORMAL, REGENERATING, NEEDS_SERVICE, UNKNOWN }

data class CommonLocation(
    val timestamp: String? = null,
    val lon: Double,
    val lat: Double
)

data class CommonTirePressure(
    val pressures: Map<String, Double>? = null // e.g., {"frontLeft": 32.0, "rearRight": 33.0}
)

data class CommonWindowStatus(
    val statuses: Map<String, String>? = null // e.g., {"frontDriver": "CLOSED", "rearPassenger": "AJAR"}
)

data class InternalBrakePadLife(
    val frontPct: Double? = null,
    val rearPct: Double? = null
)

data class CommonDtcEvent(
    val code: String,
    val description: String? = null,
    val timestamp: String? = null,
    val isActive: Boolean? = null
)

data class CommonServiceWarningsEvent(
    val warningType: String,
    val message: String? = null,
    val timestamp: String? = null,
    val severity: String? = null
)

data class CommonHAHBHC(
    val type: String, // "HardBraking", "HardAcceleration", "HarshCornering"
    val magnitude: Double? = null,
    val timestamp: String,
    val durationMs: Long? = null
)

data class CommonPredeliveryEvent(
    val eventName: String,
    val timestamp: String,
    val details: Map<String, Any>? = null
)

data class CommonDeviceEvent(
    val eventType: String,
    val timestamp: String,
    val details: Map<String, Any>? = null
)

data class CommonTelemetry(
    val location: CommonLocation? = null,
    val ignitionStatus: IgnitionStatus? = null,
    val speedCanMph: Double? = null,
    val speedGpsMph: Double? = null,
    val odometerCanMi: Double? = null,
    val odometerGpsMi: Double? = null,
    val fuelLevelPct: Double? = null,
    val fuelLevelGallon: Double? = null,
    val engineRunTimeCanHrs: Double? = null,
    val batteryVoltage: Double? = null,
    val engineCoolantTempC: Double? = null,
    val devicePowerSource: DevicePowerSource? = null,
    val dieselExhaustFluidLevel: Double? = null,
    val heading: Double? = null,
    val engineOilPressure: Double? = null,
    val ambientTemp: Double? = null,
    val milStatus: MilStatus? = null,
    val collisionState: CommonCollisionStatus? = null,
    val oilLifePct: Double? = null,
    val tirePressure: CommonTirePressure? = null,
    val engineRpm: Double? = null,
    val engineIdleTimeCanHrs: Double? = null,
    val gearPosition: GearPosition? = null,
    val windowStatus: CommonWindowStatus? = null,
    val sunRoofStatus: SunroofStatus? = null,
    val acceleration: Double? = null,
    val accelerationLat: Double? = null,
    val evEnergyRemaining: Double? = null,
    val evPlugStatus: EVPlugStatus? = null,
    val evBatteryRange: Double? = null,
    val evBatteryRangeConservative: Double? = null,
    val evBatteryLevel: Double? = null,
    val evChargingState: EvChargingState? = null,
    val evChargingEnergyAdded: Double? = null,
    val evChargingRate: Double? = null,
    val evTimeToFullCharge: Double? = null,
    val evChargingVoltage: Double? = null,
    val evChargingCurrent: Double? = null,
    val evChargeType: EvChargeModeTypes? = null,
    val evLifetimeEnergyAdded: Double? = null,
    val seatbeltStatus: SeatbeltStatus? = null,
    val cellRssiStatus: CellRssiStatus? = null,
    val dieselExhaustFilterChangeCleanLight: Light? = null,
    val engineCoolantLevel: Double? = null,
    val engineHotLight: Light? = null,
    val engineOilChangeLight: Light? = null,
    val engineOilLevelLowLight: Light? = null,
    val engineOilLight: Light? = null,
    val engineTransOilTemp: Double? = null,
    val fuelAlcoholContent: Double? = null,
    val fuelFillerCapLight: Light? = null,
    val fuelFilterLife: Double? = null,
    val fuelFilterLight: Light? = null,
    val transmissionFluidChangeLight: Light? = null,
    val waterDetectedInFuelLight: Light? = null,
    val evEnergyUsedFromDeviceInstall: Double? = null,
    val crankingVoltage: Double? = null,
    val lifetimeFuelEconomy: Double? = null,
    val lifetimeEVEfficiency: Double? = null,
    val evChargingPower: Double? = null,
    val frontPassengerSeatBeltStatus: SeatbeltStatus? = null,
    val fuelAmount: Double? = null,
    val brakePadLife: InternalBrakePadLife? = null,
    val engineAirFilterLife: Double? = null,
    val dieselExhaustFluidStatus: DieselExhaustFluidStatus? = null,
    val dieselExhaustFilterStatus: DieselExhaustFilterStatus? = null,
    val fuelLevelAdjusted: Double? = null,
    val fuelAmountAdjusted: Double? = null
)

data class CommonEvents(
    val dtcEvents: List<CommonDtcEvent>? = null,
    val serviceWarningsEvents: List<CommonServiceWarningsEvent>? = null,
    val hardBraking: List<CommonHAHBHC>? = null,
    val hardAcceleration: List<CommonHAHBHC>? = null,
    val harshCornering: List<CommonHAHBHC>? = null,
    val predeliveryEvents: List<CommonPredeliveryEvent>? = null,
    val deviceEvents: List<CommonDeviceEvent>? = null
)

data class CommonMeta(
    val additionalProperties: Map<String, Any>? = null
)

data class CommonMessageFormat<T>(
    val dateTime: String,
    val epochSource: Long,
    val vehicleId: String,
    val deviceId: String,
    val tenantId: String? = null,
    val sourceType: SourceType,
    val partitionKey: String,
    val telemetry: CommonTelemetry? = null,
    val events: CommonEvents? = null,
    val sourceSpecificData: T,
    val meta: CommonMeta? = null
)
