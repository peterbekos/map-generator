package dev.nda.worldgen

import dev.nda.fieldgen.*

object WorldBuilderModule {

    data class Params(
        val continentParams: ContinentModule.Params = ContinentModule.Params(),
        val craterParams: CraterModule.Params = CraterModule.Params(),
        val noiseParams: NoiseModule.Params = NoiseModule.Params(),
        val plateBiasParams: PlateBiasModule.Params = PlateBiasModule.Params(),
        val plateBoundaryParams: PlateBoundaryModule.Params = PlateBoundaryModule.Params(),
        val tectonicPlateParams: TectonicPlatesModule.Params = TectonicPlatesModule.Params(),
        val volcanoParams: VolcanoModule.Params = VolcanoModule.Params(),

        val terrainElevationParams: TerrainElevationModule.Params = TerrainElevationModule.Params(),
        val latitudeParams: LatitudeModule.Params = LatitudeModule.Params(),
        val fluidElevationParams: FluidElevationModule.Params = FluidElevationModule.Params(),
        val terrainMorphologyParams: TerrainMorphologyModule.Params = TerrainMorphologyModule.Params(),

        val geothermalParams: GeothermalModule.Params = GeothermalModule.Params(),
        val temperatureParams: TemperatureModule.Params = TemperatureModule.Params(),
        val airPressureParams: AirPressureModule.Params = AirPressureModule.Params(),
        val windParams: WindModule.Params = WindModule.Params(),
        val windCirculationParams: WindCirculationModule.Params = WindCirculationModule.Params()
    )

    class Fields {
        lateinit var continentFields: ContinentModule.Fields
        lateinit var craterFields: CraterModule.Fields
        lateinit var noiseFields: NoiseModule.Fields
        lateinit var plateBiasFields: PlateBiasModule.Fields
        lateinit var plateBoundaryFields: PlateBoundaryModule.Fields
        lateinit var tectonicPlatesFields: TectonicPlatesModule.Fields
        lateinit var volcanoFields: VolcanoModule.Fields

        lateinit var terrainElevationFields: TerrainElevationModule.Fields
        lateinit var latitudeFields: LatitudeModule.Fields
        lateinit var fluidElevationFields: FluidElevationModule.Fields
        lateinit var terrainMorphologyFields: TerrainMorphologyModule.Fields

        lateinit var geothermalFields: GeothermalModule.Fields
        lateinit var temperatureFields: TemperatureModule.Fields
        lateinit var airPressureFields: AirPressureModule.Fields
        lateinit var windFields: WindModule.Fields
        lateinit var windCirculationFields: WindCirculationModule.Fields
    }

    fun buildWorldFields(width: Int, height: Int, worldSeed: Long, params: Params = Params()): Fields {
        return Fields().apply {
            // No Pre Reqs
            continentFields = ContinentModule.buildContinentSigned(width, height, worldSeed, params.continentParams)
            noiseFields = NoiseModule.buildNoiseFields(width, height, worldSeed, params.noiseParams)
            tectonicPlatesFields = TectonicPlatesModule.buildTectonicPlates(width, height, worldSeed, params.tectonicPlateParams)
            craterFields = CraterModule.buildCraterFields(width, height, worldSeed, params.craterParams)
            latitudeFields = LatitudeModule.buildLatitudeFields(width, height, params.latitudeParams)

            // PreReq: Latitude
            windCirculationFields = WindCirculationModule.buildWindCirculationFields(width, height, latitudeFields, params.windCirculationParams)

            // PreReq: Tectonic Plates
            plateBiasFields = PlateBiasModule.buildPlateBiasSigned(width, height, tectonicPlatesFields, params.plateBiasParams)
            plateBoundaryFields = PlateBoundaryModule.buildBoundaryFields(width, height, tectonicPlatesFields, params.plateBoundaryParams)
            volcanoFields = VolcanoModule.buildVolcanoFields(width, height, plateBoundaryFields, worldSeed, params.volcanoParams)

            // Build Terrain Elevation
            terrainElevationFields = TerrainElevationModule.buildTerrainElevationFields(
                width, height, TerrainElevationModule.Inputs(
                    platesFields = tectonicPlatesFields,
                    continentFields = continentFields,
                    plateBiasFields = plateBiasFields,
                    plateBoundaryFields = plateBoundaryFields,
                    noiseFields = noiseFields,
                    craterFields = craterFields,
                    volcanoFields = volcanoFields

                ), params.terrainElevationParams
            )
            fluidElevationFields = FluidElevationModule.buildFluidElevationFields(width, height, terrainElevationFields, params.fluidElevationParams)
            terrainMorphologyFields = TerrainMorphologyModule.buildTerrainMorphologyFields(width, height, terrainElevationFields, fluidElevationFields, params.terrainMorphologyParams)

            // PreReq: Fluid Elevation
            geothermalFields = GeothermalModule.buildGeothermalFields(width, height, volcanoFields, plateBoundaryFields, fluidElevationFields, params.geothermalParams)
            temperatureFields = TemperatureModule.buildTemperatureFields(width, height, worldSeed, latitudeFields, fluidElevationFields, geothermalFields, params.temperatureParams)
            airPressureFields = AirPressureModule.buildAirPressureFields(width, height, worldSeed, fluidElevationFields, temperatureFields, params.airPressureParams)
            windFields = WindModule.buildWindBaseFields(
                width, height, worldSeed,
                WindModule.Inputs(
                    terrainElevationFields,
                    fluidElevationFields,
                    terrainMorphologyFields,
                    windCirculationFields
                ), params.windParams
            )
        }
    }
}