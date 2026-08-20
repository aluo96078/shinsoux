package dev.shinsou.kmp.sync.v2

import kotlinx.serialization.Serializable

@Serializable
enum class SyncEntityType {
    MANGA,
    CHAPTER,
    CATEGORY,
    EXTENSION_REPOSITORY,
}

/** Stable, portable identity. Local database ids must never be placed in [canonicalValue]. */
@Serializable
data class SyncEntityKey(
    val version: Int,
    val entityType: SyncEntityType,
    val namespace: String,
    val canonicalValue: String,
) : Comparable<SyncEntityKey> {
    init {
        require(version > 0) { "Sync entity key version must be positive" }
        require(namespace.isNotBlank()) { "Sync entity namespace cannot be blank" }
        require(namespace == normalizeNamespace(namespace)) { "Sync entity namespace is not canonical" }
        require(canonicalValue.isNotBlank()) { "Sync entity value cannot be blank" }
        require(canonicalValue == canonicalValue.trim()) { "Sync entity value cannot have surrounding whitespace" }
    }

    override fun compareTo(other: SyncEntityKey): Int = compareValuesBy(
        this,
        other,
        SyncEntityKey::version,
        { it.entityType.ordinal },
        SyncEntityKey::namespace,
        SyncEntityKey::canonicalValue,
    )

    fun stableString(): String = "$version|${entityType.name}|$namespace|$canonicalValue"

    companion object {
        fun manga(sourceIdentity: String, urlOrCanonicalId: String, version: Int = 1): SyncEntityKey =
            content(SyncEntityType.MANGA, sourceIdentity, urlOrCanonicalId, version)

        fun chapter(
            sourceIdentity: String,
            urlOrCanonicalId: String,
            version: Int = 1,
        ): SyncEntityKey = content(SyncEntityType.CHAPTER, sourceIdentity, urlOrCanonicalId, version)

        fun category(portableId: String, version: Int = 1): SyncEntityKey = SyncEntityKey(
            version = version,
            entityType = SyncEntityType.CATEGORY,
            namespace = "category",
            canonicalValue = normalizeOpaqueId(portableId),
        )

        fun defaultCategory(): SyncEntityKey = SyncEntityKey(
            version = 1,
            entityType = SyncEntityType.CATEGORY,
            namespace = "category",
            canonicalValue = "default",
        )

        fun extensionRepository(baseUrl: String, version: Int = 1): SyncEntityKey = SyncEntityKey(
            version = version,
            entityType = SyncEntityType.EXTENSION_REPOSITORY,
            namespace = "extension-repository",
            canonicalValue = normalizeUrl(baseUrl, httpsOnly = true, requireAuthority = true),
        )

        fun content(
            type: SyncEntityType,
            sourceIdentity: String,
            urlOrCanonicalId: String,
            version: Int = 1,
        ): SyncEntityKey {
            require(type == SyncEntityType.MANGA || type == SyncEntityType.CHAPTER) {
                "Content keys are only valid for manga and chapters"
            }
            val raw = urlOrCanonicalId.trim()
            val canonical = if (raw.contains("://")) {
                normalizeUrl(raw, httpsOnly = false, requireAuthority = true)
            } else {
                normalizeRelativeIdentity(raw)
            }
            return SyncEntityKey(
                version = version,
                entityType = type,
                namespace = "source:${normalizeNamespace(sourceIdentity)}",
                canonicalValue = canonical,
            )
        }

        fun normalizeNamespace(value: String): String {
            val normalized = value.trim().lowercase()
            require(normalized.isNotEmpty()) { "Namespace cannot be blank" }
            require(normalized.none { it.isWhitespace() || it == '|' }) { "Namespace contains an invalid character" }
            return normalized
        }

        fun normalizeOpaqueId(value: String): String {
            val normalized = value.trim().lowercase()
            require(normalized.isNotEmpty()) { "Portable id cannot be blank" }
            require(normalized.none { it.isWhitespace() || it == '|' }) { "Portable id contains an invalid character" }
            return normalized
        }

        /**
         * Conservative URL canonicalisation shared by every target. It deliberately preserves
         * query ordering because reordering opaque source parameters can change their meaning.
         */
        fun normalizeUrl(
            input: String,
            httpsOnly: Boolean = false,
            requireAuthority: Boolean = true,
        ): String {
            val raw = input.trim()
            val schemeEnd = raw.indexOf("://")
            require(schemeEnd > 0) { "URL must be absolute" }
            val scheme = raw.substring(0, schemeEnd).lowercase()
            require(scheme == "https" || (!httpsOnly && scheme == "http")) { "Unsupported URL scheme" }
            val remainder = raw.substring(schemeEnd + 3)
            val authorityEnd = remainder.indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it < 0) remainder.length else it }
            val authority = remainder.substring(0, authorityEnd)
            if (requireAuthority) require(authority.isNotBlank()) { "URL host cannot be blank" }
            require('@' !in authority) { "User info is not allowed in a sync identity URL" }

            val (host, port) = splitAuthority(authority)
            val canonicalHost = host.lowercase().let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
            require(canonicalHost.isNotBlank()) { "URL host cannot be blank" }
            val canonicalPort = when {
                port == null -> ""
                scheme == "https" && port == 443 -> ""
                scheme == "http" && port == 80 -> ""
                else -> ":$port"
            }

            val tailWithoutFragment = remainder.substring(authorityEnd).substringBefore('#')
            val queryIndex = tailWithoutFragment.indexOf('?')
            val rawPath = if (queryIndex < 0) tailWithoutFragment else tailWithoutFragment.substring(0, queryIndex)
            val rawQuery = if (queryIndex < 0) "" else tailWithoutFragment.substring(queryIndex + 1)
            val path = normalizePath(rawPath.ifEmpty { "/" })
            val query = if (queryIndex < 0) "" else "?${normalizePercentEncoding(rawQuery)}"
            return "$scheme://$canonicalHost$canonicalPort$path$query"
        }

        private fun normalizeRelativeIdentity(input: String): String {
            require(input.isNotBlank()) { "Content identity cannot be blank" }
            val withoutFragment = input.substringBefore('#')
            val queryIndex = withoutFragment.indexOf('?')
            val rawPath = if (queryIndex < 0) withoutFragment else withoutFragment.substring(0, queryIndex)
            val rawQuery = if (queryIndex < 0) "" else withoutFragment.substring(queryIndex + 1)
            val path = normalizePath(if (rawPath.startsWith('/')) rawPath else "/$rawPath")
            return if (queryIndex < 0) path else "$path?${normalizePercentEncoding(rawQuery)}"
        }

        private fun splitAuthority(authority: String): Pair<String, Int?> {
            if (authority.startsWith("[")) {
                val closing = authority.indexOf(']')
                require(closing > 0) { "Invalid IPv6 host" }
                val host = authority.substring(0, closing + 1)
                val suffix = authority.substring(closing + 1)
                if (suffix.isEmpty()) return host to null
                require(suffix.startsWith(':')) { "Invalid URL authority" }
                return host to parsePort(suffix.substring(1))
            }
            val lastColon = authority.lastIndexOf(':')
            if (lastColon < 0) return authority to null
            require(authority.indexOf(':') == lastColon) { "IPv6 hosts must use brackets" }
            return authority.substring(0, lastColon) to parsePort(authority.substring(lastColon + 1))
        }

        private fun parsePort(raw: String): Int {
            val port = raw.toIntOrNull()
            require(port != null && port in 1..65535) { "Invalid URL port" }
            return port
        }

        private fun normalizePath(raw: String): String {
            val segments = ArrayList<String>()
            raw.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                    else -> segments += normalizePercentEncoding(segment)
                }
            }
            val trailingSlash = raw.length > 1 && raw.endsWith('/')
            val joined = "/" + segments.joinToString("/")
            return if (trailingSlash && joined != "/") "$joined/" else joined
        }

        private fun normalizePercentEncoding(value: String): String {
            val result = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                if (value[index] == '%' && index + 2 < value.length &&
                    value[index + 1].isHexDigit() && value[index + 2].isHexDigit()
                ) {
                    val byte = "${value[index + 1]}${value[index + 2]}".toInt(16)
                    val decoded = byte.toChar()
                    if (decoded.isUnreserved()) {
                        result.append(decoded)
                    } else {
                        result.append('%')
                        result.append(value[index + 1].uppercaseChar())
                        result.append(value[index + 2].uppercaseChar())
                    }
                    index += 3
                } else {
                    result.append(value[index])
                    index += 1
                }
            }
            return result.toString()
        }

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private fun Char.isUnreserved(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
            this == '-' || this == '.' || this == '_' || this == '~'
    }
}

class SyncIdentityCollisionException(message: String) : IllegalStateException(message)

@Serializable
data class SyncIdentityMapping(
    val entityKey: SyncEntityKey,
    val localId: Long,
) {
    init {
        require(localId >= 0) { "Local ids cannot be negative" }
    }
}

@Serializable
data class SyncIdentityMap(
    val mappings: List<SyncIdentityMapping> = emptyList(),
    val blockedKeys: Set<SyncEntityKey> = emptySet(),
) {
    fun localId(key: SyncEntityKey): Long? = mappings.firstOrNull { it.entityKey == key }?.localId

    fun key(type: SyncEntityType, localId: Long): SyncEntityKey? = mappings.firstOrNull {
        it.entityKey.entityType == type && it.localId == localId
    }?.entityKey

    fun bind(key: SyncEntityKey, localId: Long): SyncIdentityMap {
        require(localId >= 0) { "Local ids cannot be negative" }
        if (key in blockedKeys) throw SyncIdentityCollisionException("Sync key is blocked after a collision: ${key.stableString()}")
        val existingId = localId(key)
        val existingKey = key(key.entityType, localId)
        if ((existingId != null && existingId != localId) || (existingKey != null && existingKey != key)) {
            val blocked = blockedKeys + key + listOfNotNull(existingKey)
            throw SyncIdentityCollisionException(
                "Identity collision for ${key.entityType} local id $localId; blocked=${blocked.joinToString { it.stableString() }}",
            )
        }
        if (existingId == localId) return this
        return copy(mappings = (mappings + SyncIdentityMapping(key, localId)).sortedBy { it.entityKey })
    }

    fun block(key: SyncEntityKey): SyncIdentityMap = copy(blockedKeys = blockedKeys + key)

    fun allocate(key: SyncEntityKey, reservedIds: Set<Long> = emptySet()): Pair<SyncIdentityMap, Long> {
        localId(key)?.let { return this to it }
        val used = mappings.asSequence()
            .filter { it.entityKey.entityType == key.entityType }
            .mapTo(mutableSetOf()) { it.localId }
            .apply { addAll(reservedIds) }
        var candidate = if (key == SyncEntityKey.defaultCategory()) 0L else 1L
        while (candidate in used) {
            check(candidate < Long.MAX_VALUE) { "No local ids remain for ${key.entityType}" }
            candidate += 1
        }
        return bind(key, candidate) to candidate
    }

    fun remap(oldKey: SyncEntityKey, newKey: SyncEntityKey): SyncIdentityMap {
        require(oldKey.entityType == newKey.entityType) { "Cannot remap across entity types" }
        require(newKey.version > oldKey.version) { "A remap must increase the entity key version" }
        return relocateAlias(oldKey, newKey)
    }

    /**
     * Relocates a device-local mapping to the reducer's deterministic terminal alias.
     *
     * Wire remaps must still increase the key version through [remap]. Concurrent wire remaps can,
     * however, create a same-version fork whose CRDT winner is the greater stable key. Projection
     * has to follow that already-validated reducer decision without making the public remap
     * contract accept arbitrary same-version moves.
     */
    internal fun relocateCanonicalAlias(oldKey: SyncEntityKey, newKey: SyncEntityKey): SyncIdentityMap {
        require(oldKey.entityType == newKey.entityType) { "Cannot relocate across entity types" }
        require(
            newKey.version > oldKey.version ||
                (newKey.version == oldKey.version && newKey > oldKey),
        ) { "A canonical alias relocation cannot downgrade or reverse deterministic key order" }
        return relocateAlias(oldKey, newKey)
    }

    private fun relocateAlias(oldKey: SyncEntityKey, newKey: SyncEntityKey): SyncIdentityMap {
        if (oldKey in blockedKeys || newKey in blockedKeys) {
            throw SyncIdentityCollisionException(
                "Cannot remap a blocked sync identity: ${oldKey.stableString()} -> ${newKey.stableString()}",
            )
        }
        val oldId = localId(oldKey) ?: return this
        val existingNewId = localId(newKey)
        if (existingNewId != null && existingNewId != oldId) {
            throw SyncIdentityCollisionException("Remap target already uses a different local id")
        }
        val withoutOld = mappings.filterNot { it.entityKey == oldKey }
        return copy(
            mappings = if (withoutOld.any { it.entityKey == newKey }) {
                withoutOld
            } else {
                withoutOld + SyncIdentityMapping(newKey, oldId)
            },
            blockedKeys = blockedKeys - oldKey,
        )
    }
}
