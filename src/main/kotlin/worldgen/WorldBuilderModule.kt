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
    }

    fun buildWorldFields(width: Int, height: Int, worldSeed: Long, params: Params = Params()): Fields {
        return Fields().apply {
            // No Pre Reqs
            continentFields = ContinentModule.buildContinentSigned(width, height, worldSeed, params.continentParams)
            noiseFields = NoiseModule.buildNoiseFields(width, height, worldSeed, params.noiseParams)
            tectonicPlatesFields =
                TectonicPlatesModule.buildTectonicPlates(width, height, worldSeed, params.tectonicPlateParams)
            craterFields = CraterModule.buildCraterFields(width, height, worldSeed, params.craterParams)

            // PreReq: Tectonic Plates
            plateBiasFields =
                PlateBiasModule.buildPlateBiasSigned(width, height, tectonicPlatesFields, params.plateBiasParams)
            plateBoundaryFields =
                PlateBoundaryModule.buildBoundaryFields(width, height, tectonicPlatesFields, params.plateBoundaryParams)
            volcanoFields =
                VolcanoModule.buildVolcanoFields(width, height, plateBoundaryFields, worldSeed, params.volcanoParams)

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
        }
    }
}