package dev.nda.fieldgen

object WeatherBaseModule {

//    data class Params()

//    data class Fields()

    data class Inputs(
//        val humidityFields: HumidityModule.Fields,
        val temperatureFields: TemperatureModule.Fields,
        val airPressureFields: AirPressureModule.Fields,
//        val worldFrameFields: WorldFrameModule.Fields,
        val windFields: WindModule.Fields
    )

//    fun buildWeatherBaseFields(
//        width: Int,
//        height: Int,
//        params: Params = Params()
//    ): Fields {
//        return Fields()
//    }
}