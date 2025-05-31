package com.example.pulsar.common

/**
 * Enumerates the various sources of vehicle telemetry data.
 * Each constant represents a distinct OEM, telematics provider, or data platform.
 */
enum class SourceType {
    Geotab, CalAmp, Gm, Toyota, Ford, DaimlerPro, FleetComplete, Volkswagen, Tesla, Bmw, Azuga, Mb, PlatformScience, Samsara, Stellantis, Rivian, Isaac, Motive, Hyundai, OmnitracsOT1
}

/**
 * Represents the ignition status of a vehicle.
 */
enum class IgnitionStatus {
    /** Vehicle ignition is ON. */
    ON,
    /** Vehicle ignition is OFF. */
    OFF,
    /** Ignition status is unknown or not reported. */
    UNKNOWN
}

/**
 * Represents the power source of the telematics device.
 */
enum class DevicePowerSource {
    /** Device is running on its internal battery. */
    BATTERY,
    /** Device is powered by an external source (e.g., vehicle battery). */
    EXTERNAL,
    /** Device power source is unknown. */
    UNKNOWN
}

/**
 * Represents the status of the Malfunction Indicator Lamp (MIL), also known as the Check Engine Light.
 */
enum class MilStatus {
    /** MIL is ON, indicating a potential issue. */
    ON,
    /** MIL is OFF. */
    OFF,
    /** MIL status is unknown. */
    UNKNOWN
}

/**
 * Represents the status of a collision detection system.
 */
enum class CommonCollisionStatus {
    /** A collision has been detected. */
    DETECTED,
    /** No collision has been detected. */
    NOT_DETECTED,
    /** Collision status is unknown. */
    UNKNOWN
}

/**
 * Represents the current gear position of the vehicle.
 */
enum class GearPosition {
    PARK, REVERSE, NEUTRAL, DRIVE, LOW, UNKNOWN
}

/**
 * Represents the status of the vehicle's sunroof.
 */
enum class SunroofStatus {
    OPEN, CLOSED, AJAR, UNKNOWN
}

/**
 * Represents the plug status for an Electric Vehicle (EV).
 */
enum class EVPlugStatus {
    /** EV is currently plugged in. */
    PLUGGED_IN,
    /** EV is not plugged in. */
    NOT_PLUGGED_IN,
    /** EV plug status is unknown. */
    UNKNOWN
}

/**
 * Represents the charging state of an Electric Vehicle (EV).
 */
enum class EvChargingState {
    /** EV is actively charging. */
    CHARGING,
    /** EV is not currently charging. */
    NOT_CHARGING,
    /** EV charging is complete. */
    COMPLETE,
    /** EV charging has encountered a fault. */
    FAULT,
    /** EV charging state is unknown. */
    UNKNOWN
}

/**
 * Represents the types of charging modes for an Electric Vehicle (EV).
 */
enum class EvChargeModeTypes {
    /** AC Level 1 charging (e.g., standard home outlet). */
    AC_LEVEL_1,
    /** AC Level 2 charging (e.g., dedicated home/public charger). */
    AC_LEVEL_2,
    /** DC Fast Charging. */
    DC_FAST_CHARGE,
    /** EV charge mode is unknown. */
    UNKNOWN
}

/**
 * Represents the status of a seatbelt.
 */
enum class SeatbeltStatus {
    /** Seatbelt is fastened. */
    FASTENED,
    /** Seatbelt is not fastened. */
    NOT_FASTENED,
    /** Seatbelt status is unknown. */
    UNKNOWN
}

/**
 * Represents the cellular signal strength (RSSI) status.
 */
enum class CellRssiStatus {
    EXCELLENT, GOOD, FAIR, POOR, UNKNOWN
}

/**
 * Represents the status of a light (e.g., warning lights).
 */
enum class Light {
    /** Light is ON. */
    ON,
    /** Light is OFF. */
    OFF,
    /** Light status is unknown. */
    UNKNOWN
}

/**
 * Represents the status of Diesel Exhaust Fluid (DEF).
 */
enum class DieselExhaustFluidStatus {
    /** DEF level is normal. */
    NORMAL,
    /** DEF level is low. */
    LOW,
    /** DEF level is critically low. */
    CRITICALLY_LOW,
    /** DEF status is unknown. */
    UNKNOWN
}

/**
 * Represents the status of the Diesel Particulate Filter (DPF).
 */
enum class DieselExhaustFilterStatus {
    /** DPF status is normal. */
    NORMAL,
    /** DPF is currently regenerating. */
    REGENERATING,
    /** DPF needs service. */
    NEEDS_SERVICE,
    /** DPF status is unknown. */
    UNKNOWN
}

/**
 * Represents a geographic location with timestamp.
 * @property timestamp ISO 8601 timestamp of when the location was recorded.
 * @property lon Longitude coordinate.
 * @property lat Latitude coordinate.
 */
data class CommonLocation(
    val timestamp: String? = null,
    val lon: Double,
    val lat: Double
)

/**
 * Represents tire pressure readings for various tires.
 * @property pressures A map where keys are tire positions (e.g., "frontLeft", "rearRight") and values are pressures in PSI.
 */
data class CommonTirePressure(
    val pressures: Map<String, Double>? = null // e.g., {"frontLeft": 32.0, "rearRight": 33.0}
)

/**
 * Represents the status of vehicle windows.
 * @property statuses A map where keys are window positions (e.g., "frontDriver", "rearPassenger") and values are their statuses (e.g., "CLOSED", "AJAR").
 */
data class CommonWindowStatus(
    val statuses: Map<String, String>? = null // e.g., {"frontDriver": "CLOSED", "rearPassenger": "AJAR"}
)

/**
 * Represents the remaining life percentage of internal brake pads.
 * @property frontPct Remaining life percentage of the front brake pads.
 * @property rearPct Remaining life percentage of the rear brake pads.
 */
data class InternalBrakePadLife(
    val frontPct: Double? = null,
    val rearPct: Double? = null
)

/**
 * Represents a Diagnostic Trouble Code (DTC) event.
 * @property code The DTC code (e.g., "P0420").
 * @property description A description of the DTC.
 * @property timestamp ISO 8601 timestamp of when the DTC event occurred or was recorded.
 * @property isActive Indicates if the DTC is currently active.
 */
data class CommonDtcEvent(
    val code: String,
    val description: String? = null,
    val timestamp: String? = null,
    val isActive: Boolean? = null
)

/**
 * Represents a common service warning event.
 * @property warningType Type of the service warning (e.g., "OilChangeDue", "LowWasherFluid").
 * @property message A descriptive message for the warning.
 * @property timestamp ISO 8601 timestamp of when the warning was triggered.
 * @property severity Severity of the warning (e.g., "INFO", "WARN", "CRITICAL").
 */
data class CommonServiceWarningsEvent(
    val warningType: String,
    val message: String? = null,
    val timestamp: String? = null,
    val severity: String? = null
)

/**
 * Represents a Hard Acceleration, Hard Braking, or Harsh Cornering (HAHBHC) event.
 * @property type Type of the event: "HardBraking", "HardAcceleration", or "HarshCornering".
 * @property magnitude Optional magnitude of the event (e.g., G-force).
 * @property timestamp ISO 8601 timestamp of when the event occurred.
 * @property durationMs Optional duration of the event in milliseconds.
 */
data class CommonHAHBHC(
    val type: String, // "HardBraking", "HardAcceleration", "HarshCornering"
    val magnitude: Double? = null,
    val timestamp: String,
    val durationMs: Long? = null
)

/**
 * Represents a pre-delivery event, typically related to vehicle manufacturing or initial setup.
 * @property eventName Name of the pre-delivery event.
 * @property timestamp ISO 8601 timestamp of the event.
 * @property details A map containing additional details about the event.
 */
data class CommonPredeliveryEvent(
    val eventName: String,
    val timestamp: String,
    val details: Map<String, Any>? = null
)

/**
 * Represents a generic device-related event (e.g., device tampering, GPS signal lost/acquired).
 * @property eventType Type of the device event.
 * @property timestamp ISO 8601 timestamp of the event.
 * @property details A map containing additional details about the event.
 */
data class CommonDeviceEvent(
    val eventType: String,
    val timestamp: String,
    val details: Map<String, Any>? = null
)

/**
 * Container for common telemetry data points from a vehicle.
 * All properties are optional to accommodate variations in data availability from different sources.
 */
data class CommonTelemetry(
    /** Geographic location data. */
    val location: CommonLocation? = null,
    /** Ignition status of the vehicle. */
    val ignitionStatus: IgnitionStatus? = null,
    /** Vehicle speed as reported by the CAN bus, in miles per hour. */
    val speedCanMph: Double? = null,
    /** Vehicle speed as reported by GPS, in miles per hour. */
    val speedGpsMph: Double? = null,
    /** Odometer reading from the CAN bus, in miles. */
    val odometerCanMi: Double? = null,
    /** Odometer reading calculated from GPS data, in miles. */
    val odometerGpsMi: Double? = null,
    /** Fuel level as a percentage of tank capacity. */
    val fuelLevelPct: Double? = null,
    /** Fuel level in gallons. */
    val fuelLevelGallon: Double? = null,
    /** Engine run time as reported by the CAN bus, in hours. */
    val engineRunTimeCanHrs: Double? = null,
    /** Vehicle battery voltage. */
    val batteryVoltage: Double? = null,
    /** Engine coolant temperature, in Celsius. */
    val engineCoolantTempC: Double? = null,
    /** Power source of the telematics device. */
    val devicePowerSource: DevicePowerSource? = null,
    /** Diesel Exhaust Fluid (DEF) level, typically as a percentage or volume. */
    val dieselExhaustFluidLevel: Double? = null,
    /** Vehicle heading or direction, in degrees from True North. */
    val heading: Double? = null,
    /** Engine oil pressure, typically in PSI or kPa. */
    val engineOilPressure: Double? = null,
    /** Ambient air temperature, typically in Celsius or Fahrenheit. */
    val ambientTemp: Double? = null,
    /** Status of the Malfunction Indicator Lamp (Check Engine Light). */
    val milStatus: MilStatus? = null,
    /** Status of collision detection system. */
    val collisionState: CommonCollisionStatus? = null,
    /** Remaining engine oil life, as a percentage. */
    val oilLifePct: Double? = null,
    /** Tire pressure readings. */
    val tirePressure: CommonTirePressure? = null,
    /** Engine speed, in revolutions per minute (RPM). */
    val engineRpm: Double? = null,
    /** Engine idle time as reported by the CAN bus, in hours. */
    val engineIdleTimeCanHrs: Double? = null,
    /** Current gear position of the vehicle. */
    val gearPosition: GearPosition? = null,
    /** Status of vehicle windows. */
    val windowStatus: CommonWindowStatus? = null,
    /** Status of the vehicle's sunroof. */
    val sunRoofStatus: SunroofStatus? = null,
    /** Longitudinal acceleration (e.g., G-force). */
    val acceleration: Double? = null,
    /** Lateral acceleration (e.g., G-force). */
    val accelerationLat: Double? = null,
    /** For Electric Vehicles (EVs): Remaining battery energy, typically in kWh. */
    val evEnergyRemaining: Double? = null,
    /** For EVs: Plug status (plugged in or not). */
    val evPlugStatus: EVPlugStatus? = null,
    /** For EVs: Estimated battery range, in miles or kilometers. */
    val evBatteryRange: Double? = null,
    /** For EVs: Conservative estimate of battery range. */
    val evBatteryRangeConservative: Double? = null,
    /** For EVs: Battery state of charge (SoC), as a percentage. */
    val evBatteryLevel: Double? = null,
    /** For EVs: Current charging state. */
    val evChargingState: EvChargingState? = null,
    /** For EVs: Energy added during the current charging session, typically in kWh. */
    val evChargingEnergyAdded: Double? = null,
    /** For EVs: Current charging rate, typically in kW. */
    val evChargingRate: Double? = null,
    /** For EVs: Estimated time until the battery is fully charged, in minutes or hours. */
    val evTimeToFullCharge: Double? = null,
    /** For EVs: Charging voltage. */
    val evChargingVoltage: Double? = null,
    /** For EVs: Charging current, in Amperes. */
    val evChargingCurrent: Double? = null,
    /** For EVs: Type of charging mode (e.g., AC Level 1, DC Fast Charge). */
    val evChargeType: EvChargeModeTypes? = null,
    /** For EVs: Lifetime energy added to the battery, typically in kWh. */
    val evLifetimeEnergyAdded: Double? = null,
    /** Status of the driver's seatbelt. */
    val seatbeltStatus: SeatbeltStatus? = null, // Assuming driver's seatbelt by default
    /** Cellular signal strength (RSSI) status. */
    val cellRssiStatus: CellRssiStatus? = null,
    /** Status of the Diesel Particulate Filter (DPF) change/clean light. */
    val dieselExhaustFilterChangeCleanLight: Light? = null,
    /** Engine coolant level, typically as a percentage or raw value. */
    val engineCoolantLevel: Double? = null,
    /** Status of the engine hot/overheating light. */
    val engineHotLight: Light? = null,
    /** Status of the engine oil change reminder light. */
    val engineOilChangeLight: Light? = null,
    /** Status of the engine oil level low light. */
    val engineOilLevelLowLight: Light? = null,
    /** Status of the general engine oil warning light. */
    val engineOilLight: Light? = null,
    /** Engine transmission oil temperature, typically in Celsius or Fahrenheit. */
    val engineTransOilTemp: Double? = null,
    /** Fuel alcohol content, as a percentage (for flex-fuel vehicles). */
    val fuelAlcoholContent: Double? = null,
    /** Status of the fuel filler cap warning light. */
    val fuelFillerCapLight: Light? = null,
    /** Remaining life of the fuel filter, as a percentage or distance. */
    val fuelFilterLife: Double? = null,
    /** Status of the fuel filter warning light. */
    val fuelFilterLight: Light? = null,
    /** Status of the transmission fluid change reminder light. */
    val transmissionFluidChangeLight: Light? = null,
    /** Status of the water-in-fuel detection light. */
    val waterDetectedInFuelLight: Light? = null,
    /** For EVs: Energy used since device installation, typically in kWh. */
    val evEnergyUsedFromDeviceInstall: Double? = null,
    /** Cranking voltage during engine start. */
    val crankingVoltage: Double? = null,
    /** Lifetime fuel economy of the vehicle (e.g., miles per gallon). */
    val lifetimeFuelEconomy: Double? = null,
    /** For EVs: Lifetime energy efficiency (e.g., miles per kWh). */
    val lifetimeEVEfficiency: Double? = null,
    /** For EVs: Current charging power, in kW. (May be redundant with evChargingRate but included for compatibility). */
    val evChargingPower: Double? = null,
    /** Status of the front passenger's seatbelt. */
    val frontPassengerSeatBeltStatus: SeatbeltStatus? = null,
    /** Fuel amount in the tank, typically in gallons or liters (may be redundant with fuelLevelGallon). */
    val fuelAmount: Double? = null,
    /** Remaining life of internal brake pads. */
    val brakePadLife: InternalBrakePadLife? = null,
    /** Remaining life of the engine air filter, as a percentage. */
    val engineAirFilterLife: Double? = null,
    /** Status of Diesel Exhaust Fluid (DEF). */
    val dieselExhaustFluidStatus: DieselExhaustFluidStatus? = null,
    /** Status of the Diesel Particulate Filter (DPF). */
    val dieselExhaustFilterStatus: DieselExhaustFilterStatus? = null,
    /** Adjusted fuel level percentage, potentially after calibration or corrections. */
    val fuelLevelAdjusted: Double? = null,
    /** Adjusted fuel amount, potentially after calibration or corrections. */
    val fuelAmountAdjusted: Double? = null
)

/**
 * Container for various event types that can occur in a vehicle.
 * All properties are lists of specific event types and are optional.
 */
data class CommonEvents(
    /** List of Diagnostic Trouble Code (DTC) events. */
    val dtcEvents: List<CommonDtcEvent>? = null,
    /** List of service warning events. */
    val serviceWarningsEvents: List<CommonServiceWarningsEvent>? = null,
    /** List of hard braking events. */
    val hardBraking: List<CommonHAHBHC>? = null,
    /** List of hard acceleration events. */
    val hardAcceleration: List<CommonHAHBHC>? = null,
    /** List of harsh cornering events. */
    val harshCornering: List<CommonHAHBHC>? = null,
    /** List of pre-delivery events. */
    val predeliveryEvents: List<CommonPredeliveryEvent>? = null,
    /** List of generic device events. */
    val deviceEvents: List<CommonDeviceEvent>? = null
)

/**
 * Container for metadata or any additional properties not fitting into the standard CMF structure.
 * @property additionalProperties A map for storing arbitrary key-value pairs.
 */
data class CommonMeta(
    val additionalProperties: Map<String, Any>? = null
)

/**
 * The main structure for the Common Message Format (CMF) for vehicle telemetry.
 * This generic class wraps telemetry data, event data, and source-specific information.
 *
 * @param T The type of the source-specific data payload.
 * @property dateTime ISO 8601 timestamp indicating when the event occurred or the data was captured, according to the source.
 * @property epochSource Unix epoch timestamp (in seconds) from the source, corresponding to `dateTime`.
 * @property vehicleId Unique identifier for the vehicle (e.g., VIN).
 * @property deviceId Unique identifier for the telematics device reporting the data.
 * @property tenantId Optional identifier for the tenant or customer associated with the vehicle/device.
 * @property sourceType The original source of the data (e.g., Geotab, CalAmp).
 * @property partitionKey Key used for partitioning messages in Pulsar, often derived from `vehicleId` or `deviceId`.
 * @property telemetry Optional container for standardized [CommonTelemetry] data points.
 * @property events Optional container for standardized [CommonEvents].
 * @property sourceSpecificData Payload of type [T] containing data unique to the source, or the original raw data.
 * @property meta Optional container for [CommonMeta] or any other additional properties.
 */
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
