package com.gigasoft.runtime

import com.gigasoft.api.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class RuntimeRegistry(private val pluginId: String) : RegistryFacade {
    private val items = CopyOnWriteArrayList<ItemDefinition>()
    private val blocks = CopyOnWriteArrayList<BlockDefinition>()
    private val recipes = CopyOnWriteArrayList<RecipeDefinition>()
    private val machines = CopyOnWriteArrayList<MachineDefinition>()
    private val textures = CopyOnWriteArrayList<TextureDefinition>()
    private val models = CopyOnWriteArrayList<ModelDefinition>()
    private val animations = CopyOnWriteArrayList<AnimationDefinition>()
    private val sounds = CopyOnWriteArrayList<SoundDefinition>()
    private val systems = ConcurrentHashMap<String, TickSystem>()
    private val itemIds = ConcurrentHashMap.newKeySet<String>()
    private val blockIds = ConcurrentHashMap.newKeySet<String>()
    private val recipeIds = ConcurrentHashMap.newKeySet<String>()
    private val machineIds = ConcurrentHashMap.newKeySet<String>()
    private val textureIds = ConcurrentHashMap.newKeySet<String>()
    private val modelIds = ConcurrentHashMap.newKeySet<String>()
    private val animationIds = ConcurrentHashMap.newKeySet<String>()
    private val soundIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var systemsSnapshotCache: List<Pair<String, TickSystem>> = emptyList()
    @Volatile
    private var systemsSnapshotDirty = true
    private val systemsVersionCounter = AtomicLong(0)

    override fun registerItem(definition: ItemDefinition) {
        ensureUnique(itemIds, definition.id, "item")
        items += definition
    }

    override fun registerBlock(definition: BlockDefinition) {
        ensureUnique(blockIds, definition.id, "block")
        blocks += definition
    }

    override fun registerRecipe(definition: RecipeDefinition) {
        ensureUnique(recipeIds, definition.id, "recipe")
        recipes += definition
    }

    override fun registerMachine(definition: MachineDefinition) {
        ensureUnique(machineIds, definition.id, "machine")
        machines += definition
    }

    override fun registerTexture(definition: TextureDefinition) {
        ensureUnique(textureIds, definition.id, "texture")
        textures += definition
    }

    override fun registerModel(definition: ModelDefinition) {
        ensureUnique(modelIds, definition.id, "model")
        models += definition
    }

    override fun registerAnimation(definition: AnimationDefinition) {
        ensureUnique(animationIds, definition.id, "animation")
        animations += definition
    }

    override fun registerSound(definition: SoundDefinition) {
        ensureUnique(soundIds, definition.id, "sound")
        sounds += definition
    }

    override fun registerSystem(id: String, system: TickSystem) {
        val existing = systems.putIfAbsent(id, system)
        require(existing == null) { "Duplicate system id '$id' in plugin '$pluginId'" }
        systemsVersionCounter.incrementAndGet()
        systemsSnapshotDirty = true
    }

    override fun items(): List<ItemDefinition> = items.toList()
    override fun blocks(): List<BlockDefinition> = blocks.toList()
    override fun recipes(): List<RecipeDefinition> = recipes.toList()
    override fun machines(): List<MachineDefinition> = machines.toList()
    override fun textures(): List<TextureDefinition> = textures.toList()
    override fun models(): List<ModelDefinition> = models.toList()
    override fun animations(): List<AnimationDefinition> = animations.toList()
    override fun sounds(): List<SoundDefinition> = sounds.toList()
    override fun validateAssets(options: ResourcePackBundleOptions): AssetValidationResult {
        val issues = mutableListOf<AssetValidationIssue>()
        val textureIds = textures.map { it.id }.toSet()
        val modelIds = models.map { it.id }.toSet()

        textures.forEach { texture ->
            if (texture.path.isBlank()) {
                issues += error("ASSET_TEXTURE_PATH_EMPTY", "Texture path must not be empty", texture.id)
            } else {
                validateNamespacePath(texture.path, texture.id, "ASSET_TEXTURE_NAMESPACE", issues)
                validateExtension(texture.path, setOf(".png", ".jpg", ".jpeg", ".webp"), "ASSET_TEXTURE_FORMAT", texture.id, issues)
            }
        }

        models.forEach { model ->
            if (model.geometryPath.isBlank()) {
                issues += error("ASSET_MODEL_PATH_EMPTY", "Model geometryPath must not be empty", model.id)
            } else {
                validateNamespacePath(model.geometryPath, model.id, "ASSET_MODEL_NAMESPACE", issues)
            }
            val normalizedFormat = model.format.trim().lowercase()
            if (normalizedFormat == "json") {
                validateExtension(model.geometryPath, setOf(".json"), "ASSET_MODEL_FORMAT", model.id, issues)
            } else if (normalizedFormat == "gltf") {
                validateExtension(model.geometryPath, setOf(".gltf", ".glb"), "ASSET_MODEL_FORMAT", model.id, issues)
            } else {
                issues += warning("ASSET_MODEL_FORMAT_UNKNOWN", "Unknown model format '${model.format}'", model.id)
            }
            if (options.validateReferences) {
                model.textures.forEach { (slot, textureId) ->
                    if (textureId !in textureIds) {
                        issues += error(
                            "ASSET_MODEL_TEXTURE_MISSING",
                            "Model references missing texture '$textureId' at slot '$slot'",
                            model.id
                        )
                    }
                }
            }
            model.lods.forEachIndexed { index, lod ->
                validateNamespacePath(lod.geometryPath, model.id, "ASSET_MODEL_LOD_NAMESPACE", issues)
                val lodExt = if (lod.format.equals("gltf", ignoreCase = true)) {
                    setOf(".gltf", ".glb")
                } else {
                    setOf(".json")
                }
                validateExtension(lod.geometryPath, lodExt, "ASSET_MODEL_LOD_FORMAT", "${model.id}#lod$index", issues)
            }
            model.animations.forEach { (name, path) ->
                validateNamespacePath(path, model.id, "ASSET_MODEL_ANIMATION_NAMESPACE", issues)
                validateExtension(path, setOf(".json"), "ASSET_MODEL_ANIMATION_FORMAT", "${model.id}:$name", issues)
            }
        }

        animations.forEach { animation ->
            if (animation.path.isBlank()) {
                issues += error("ASSET_ANIMATION_PATH_EMPTY", "Animation path must not be empty", animation.id)
            } else {
                validateNamespacePath(animation.path, animation.id, "ASSET_ANIMATION_NAMESPACE", issues)
                validateExtension(animation.path, setOf(".json"), "ASSET_ANIMATION_FORMAT", animation.id, issues)
            }
            val targetModel = animation.targetModelId?.trim()
            if (options.validateReferences && !targetModel.isNullOrEmpty() && targetModel !in modelIds) {
                issues += error(
                    "ASSET_ANIMATION_TARGET_MISSING",
                    "Animation targets missing model '$targetModel'",
                    animation.id
                )
            }
        }

        sounds.forEach { sound ->
            if (sound.path.isBlank()) {
                issues += error("ASSET_SOUND_PATH_EMPTY", "Sound path must not be empty", sound.id)
            } else {
                validateNamespacePath(sound.path, sound.id, "ASSET_SOUND_NAMESPACE", issues)
                validateExtension(sound.path, setOf(".ogg", ".wav", ".mp3"), "ASSET_SOUND_FORMAT", sound.id, issues)
            }
            if (sound.volume < 0.0) {
                issues += error("ASSET_SOUND_VOLUME", "Sound volume must be >= 0.0", sound.id)
            }
            if (sound.pitch <= 0.0) {
                issues += error("ASSET_SOUND_PITCH", "Sound pitch must be > 0.0", sound.id)
            }
        }

        val valid = issues.none { it.severity == AssetValidationSeverity.ERROR }
        return AssetValidationResult(valid = valid, issues = issues)
    }

    override fun buildResourcePackBundle(options: ResourcePackBundleOptions): ResourcePackBundle {
        val validation = validateAssets(options)
        if (options.strict && !validation.valid) {
            val message = validation.issues
                .filter { it.severity == AssetValidationSeverity.ERROR }
                .joinToString("; ") { issue ->
                    val idPart = issue.assetId?.let { "[$it] " } ?: ""
                    "${issue.code}: $idPart${issue.message}"
                }
            throw IllegalStateException("Asset validation failed for plugin '$pluginId': $message")
        }
        val assets = mutableListOf<ResourcePackAsset>()
        textures.forEach { texture ->
            assets += ResourcePackAsset(
                id = texture.id,
                type = ResourceAssetType.TEXTURE,
                path = texture.path,
                metadata = mapOf(
                    "category" to texture.category,
                    "animated" to texture.animated.toString()
                )
            )
        }
        models.forEach { model ->
            assets += ResourcePackAsset(id = model.id, type = ResourceAssetType.MODEL_GEOMETRY, path = model.geometryPath)
            model.lods.forEachIndexed { index, lod ->
                assets += ResourcePackAsset(
                    id = "${model.id}#lod$index",
                    type = ResourceAssetType.MODEL_LOD,
                    path = lod.geometryPath,
                    metadata = mapOf("distance" to lod.distance.toString(), "format" to lod.format)
                )
            }
            model.animations.forEach { (name, path) ->
                assets += ResourcePackAsset(
                    id = "${model.id}:$name",
                    type = ResourceAssetType.MODEL_ANIMATION,
                    path = path
                )
            }
        }
        animations.forEach { animation ->
            assets += ResourcePackAsset(
                id = animation.id,
                type = ResourceAssetType.ANIMATION,
                path = animation.path,
                metadata = buildMap {
                    animation.targetModelId?.let { put("targetModelId", it) }
                    put("loop", animation.loop.toString())
                }
            )
        }
        sounds.forEach { sound ->
            assets += ResourcePackAsset(
                id = sound.id,
                type = ResourceAssetType.SOUND,
                path = sound.path,
                metadata = mapOf(
                    "category" to sound.category,
                    "stream" to sound.stream.toString(),
                    "volume" to sound.volume.toString(),
                    "pitch" to sound.pitch.toString()
                )
            )
        }
        return ResourcePackBundle(
            pluginId = pluginId,
            textures = textures(),
            models = models(),
            animations = animations(),
            sounds = sounds(),
            assets = assets
        )
    }
    override fun systems(): Map<String, TickSystem> = systems.toMap()
    fun systemsView(): Set<Map.Entry<String, TickSystem>> = systems.entries
    fun systemsVersion(): Long = systemsVersionCounter.get()
    fun systemsSnapshot(): List<Pair<String, TickSystem>> {
        if (!systemsSnapshotDirty) return systemsSnapshotCache
        synchronized(this) {
            if (!systemsSnapshotDirty) return systemsSnapshotCache
            systemsSnapshotCache = systems.entries.map { it.key to it.value }
            systemsSnapshotDirty = false
            return systemsSnapshotCache
        }
    }

    private fun ensureUnique(existing: MutableSet<String>, id: String, kind: String) {
        require(existing.add(id)) { "Duplicate $kind id '$id' in plugin '$pluginId'" }
    }

    private fun validateNamespacePath(
        path: String,
        assetId: String,
        code: String,
        issues: MutableList<AssetValidationIssue>
    ) {
        if (!path.startsWith("assets/$pluginId/")) {
            issues += error(
                code,
                "Asset path '$path' must start with 'assets/$pluginId/'",
                assetId
            )
        }
    }

    private fun validateExtension(
        path: String,
        allowed: Set<String>,
        code: String,
        assetId: String,
        issues: MutableList<AssetValidationIssue>
    ) {
        val lower = path.lowercase()
        if (allowed.none { lower.endsWith(it) }) {
            issues += error(
                code,
                "Asset path '$path' must end with one of ${allowed.joinToString()}",
                assetId
            )
        }
    }

    private fun error(code: String, message: String, assetId: String? = null): AssetValidationIssue {
        return AssetValidationIssue(
            severity = AssetValidationSeverity.ERROR,
            code = code,
            message = message,
            assetId = assetId
        )
    }

    private fun warning(code: String, message: String, assetId: String? = null): AssetValidationIssue {
        return AssetValidationIssue(
            severity = AssetValidationSeverity.WARNING,
            code = code,
            message = message,
            assetId = assetId
        )
    }
}
