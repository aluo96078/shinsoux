package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration

/**
 * App-shipped code-review boundary for the unsigned official legacy-Shinsou publication.
 *
 * Execution requires official provenance or the explicitly enabled, exact reviewed loopback
 * development repository. Other byte-identical mirrors retain only the separate CDN content
 * policy; they cannot mint in-process execution authority.
 */
internal object OfficialShinsouReviewedCatalog {
    private val ordinaryCapabilities = setOf(
        "CATALOGUE", "LATEST", "BROWSE", "METADATA", "UNITS", "CONTENT", "SEARCH",
    )
    private val ordinaryPermissions = setOf(
        PluginRuntimePermission.EXECUTE_SCRIPT,
        PluginRuntimePermission.NETWORK,
    )
    private val loginPermissions = ordinaryPermissions + setOf(
        PluginRuntimePermission.COOKIE_STORAGE,
        PluginRuntimePermission.CREDENTIAL_ACCESS,
        PluginRuntimePermission.BROWSER_CHALLENGE,
    )
    private val noEvents = PluginSystemEventDeclaration(1, 1)
    private val loginRequired = PluginSystemEventDeclaration(
        1,
        1,
        required = setOf("command.auth.login.request"),
    )
    private val loginOptional = PluginSystemEventDeclaration(
        1,
        1,
        optional = setOf("command.auth.login.request"),
    )

    private val publishedProfiles = listOf(
        profile("eh.ehentai", "E-Hentai", "1.1.9", 11, "all", true, 6_912_170L,
            "https://e-hentai.org", "564695e574d630d1336941adf2e5da11785ff83001c05229ab71e11a54bda485",
            requestOrigins = setOf("https://e-hentai.org", "https://api.e-hentai.org"),
            contentOrigins = setOf("https://ehgt.org"),
            contentHostSuffixes = setOf(REVIEWED_DYNAMIC_CONTENT_SUFFIX)),
        profile("all.nhentai", "NHentai", "2.0.1", 3, "all", true, 7_309_872L,
            "https://nhentai.net", "193d6d89e51fa252ad695f839fe54ef0ab0c154d3fb75718dae67fceaa874a8f",
            // The reviewed source obtains these eight exact origins from NHentai's public
            // /api/v2/cdn response. Keep them content-only: catalogue API calls and credentials
            // remain confined to nhentai.net, while covers/pages can be fetched by the host.
            contentOrigins = setOf(
                "https://i1.nhentai.net", "https://i2.nhentai.net",
                "https://i3.nhentai.net", "https://i4.nhentai.net",
                "https://t1.nhentai.net", "https://t2.nhentai.net",
                "https://t3.nhentai.net", "https://t4.nhentai.net",
            )),
        profile("zh.jinmantiantang", "禁漫天堂", "1.0.7", 8, "zh", true, 1_817_081L,
            "https://18comic.vip", "f45a1485ac720d9a1963c7cecde874926c3e727f209c5534e04b49b544ebfb27",
            // Both exact CDN origins have been observed in public catalogue responses.
            contentOrigins = setOf("https://cdn-msp.18comic.vip", "https://cdn-msp2.18comic.vip")),
        profile("zh.baozimh", "包子漫画", "1.0.6", 7, "zh", false, 4_502_917L,
            "https://www.baozimh.com", "6dbafe5e7bc81fdf90a565e8a7f6dfd396de833cadfea6009530b95726e880e6",
            requestOrigins = setOf(
                "https://www.baozimh.com", "https://app.baozimh.com", "https://appgb.baozimh.com",
            ),
            // Public chapter pages currently emit both the legacy bzcdn hostname and the
            // newer baozicdn hostname. They are image-only origins; app mirrors remain
            // request/credential origins and no additional script capability is granted.
            contentOrigins = setOf(
                "https://s1.bzcdn.net", "https://s2.bzcdn.net", "https://s1.baozicdn.com",
                // Verified on the public /classify catalogue: cover thumbnails are served from
                // this exact host while chapter pages continue to use the two origins above.
                "https://static-tw.baozimh.com",
            )),
        profile("zh.bika", "哔咔漫画", "1.0.12", 13, "zh", true, 8_123_456L,
            "https://manhuabika.com", "cd09553432d85389de169017aeeadd776f539183f13598c2259b4fec738cbdf1",
            runtimePermissions = loginPermissions,
            hostPermissions = setOf(PluginHostPermission.REQUEST_LOGIN_UI),
            events = loginOptional,
            requestOrigins = setOf("https://manhuabika.com", "https://picaapi.go2778.com"),
            browserSessionOrigins = setOf("https://picaapi.go2778.com"),
            // Both observed storage hosts redirect images to this exact image CDN.
            contentOrigins = setOf(
                "https://storage1.picacomic.com", "https://storage-b.picacomic.com",
                "https://img.picacomic.com",
            ),
            // The embedded document is the public source site. The API origin above remains the
            // separately reviewed browser-backed request authority declared by the manifest.
            webChallengeOrigins = setOf("https://manhuabika.com"),
            // The foreground site's line probe and login call this reviewed API origin too.
            // Request-plane authority does not automatically grant WebView subresource access.
            webChallengeSubresourceOrigins = setOf(
                "https://manhuabika.com", "https://picaapi.go2778.com",
            )),
        profile("zh.manhuagui", "漫画柜", "1.1.6", 8, "zh", true, 6_301_748L,
            "https://tw.manhuagui.com", "a984f5e3594b57a821f56137d3817f69487eb35ddde53f8175fc648b8a626e53",
            contentOrigins = setOf("https://i.hamreus.com", "https://cf.mhgui.com")),
        profile("zh.komiic", "Komiic", "1.2.0", 4, "zh", true, 8_104_923L,
            "https://komiic.com", "5a450bf1480675f9be5993188221e49fb350338217511eb8e61fe90e8b178731",
            // Catalogue/detail responses expose public.komiic.com cover URLs while reader
            // tickets point at img.komiic.com. Both are credential-free image origins; API and
            // ticket issuance remain confined to komiic.com.
            contentOrigins = setOf("https://public.komiic.com", "https://img.komiic.com")),
        profile("zh.wnacg", "紳士漫畫", "1.3.2", 6, "zh", true, 5_209_831L,
            "https://www.wnacg.com", "36e4728f9207e640589fd0216eaa7cde2f5212341c5b78c6ab71d8a3d2972b9e",
            // WNACG's gallery JavaScript uses this host when fast_img_host is absent.
            contentOrigins = setOf("https://img5.qy0.ru", "https://t4.qy0.ru")),
        profile("zh.mycomic", "MyComic", "1.0.0", 1, "zh", true, 9_119_537_447_562_549_661L,
            "https://mycomic.com", "7bc6502b5b418c0d640f84adc1fd44a83f38fa533603591300798fe45d798ad0",
            contentOrigins = setOf("https://biccam.com")),
        profile("zh.dm5", "動漫屋", "1.4.0", 5, "zh", true, 3_947_628L,
            "https://www.dm5.com", "947a6a2e75f3415432a45ac84ea50a50ff70ed26b0eb993419a56bad84850642",
            runtimePermissions = loginPermissions,
            hostPermissions = setOf(PluginHostPermission.REQUEST_LOGIN_UI),
            // The public /manhua-new catalogue currently distributes covers across these nine
            // exact hosts. Keep CSS/static hosts and the broader cdndm5.com family denied.
            contentOrigins = setOf(
                "https://mhfm1tw.cdndm5.com", "https://mhfm2tw.cdndm5.com",
                "https://mhfm3tw.cdndm5.com", "https://mhfm4tw.cdndm5.com",
                "https://mhfm5tw.cdndm5.com", "https://mhfm6tw.cdndm5.com",
                "https://mhfm7tw.cdndm5.com", "https://mhfm8tw.cdndm5.com",
                "https://mhfm9tw.cdndm5.com",
            )),
        profile("zh.manhuaren", "漫画人", "1.0.0", 1, "zh", false, 3_616_827_811_449_702_173L,
            "https://www.manhuaren.com", "3d16db2fee1044211b83d11d3d9ab9daae4b52e0d98817527bc99028fb07baa1",
            // Public catalogue and chapter responses use a small rotating set of mhfm*tw image
            // hosts. Keep each observed host explicit and content-only; the source page/API
            // origin remains the only request origin and no credentials are sent to the CDN.
            contentOrigins = setOf(
                "https://mhfm1tw.cdndm5.com", "https://mhfm2tw.cdndm5.com",
                "https://mhfm3tw.cdndm5.com", "https://mhfm4tw.cdndm5.com",
                "https://mhfm5tw.cdndm5.com", "https://mhfm6tw.cdndm5.com",
                "https://mhfm7tw.cdndm5.com", "https://mhfm8tw.cdndm5.com",
                "https://mhfm9tw.cdndm5.com",
                // One public reader response currently uses this exact page-image shard. It is
                // content-only; no general cdndm5.com suffix or credential authority is added.
                "https://manhua1041zjcdn63.cdndm5.com",
                "https://manhua1041zjcdn79.cdndm5.com",
            )),
        profile("zh.mangacopy", "拷貝漫畫", "1.0.0", 1, "zh", true, 6_696_312_508_930_833_206L,
            "https://www.mangacopy.com", "8da992d2eab8c5b1030b0c9c617e1447645ce8ac3442145760b63f28f62cd8cb",
            requestOrigins = setOf(
                "https://www.mangacopy.com", "https://api.manga2026.xyz",
                "https://mapi.hotmangasg.com", "https://mapi.hotmangasd.com",
                "https://mapi.hotmangasf.com", "https://mapi.elfgjfghkk.club",
                "https://mapi.fgjfghkkcenter.club", "https://mapi.fgjfghkk.club",
                "https://api.copy202602.com", "https://www.2026copy.com",
                "https://www.copy3000.com", "https://www.copy20.com",
            ),
            // Verified from the public /comics catalogue. Covers are sharded by initial across
            // this observed finite set; do not grant a suffix wildcard or send credentials.
            contentOrigins = setOf(
                "https://s3.mangafunb.fun", "https://sb.mangafunb.fun",
                "https://sc.mangafunb.fun", "https://sd.mangafunb.fun",
                "https://se.mangafunb.fun", "https://sf.mangafunb.fun",
                "https://sg.mangafunb.fun", "https://sj.mangafunb.fun",
                "https://sl.mangafunb.fun", "https://sm.mangafunb.fun",
                "https://sn.mangafunb.fun", "https://so.mangafunb.fun",
                "https://sq.mangafunb.fun", "https://ss.mangafunb.fun",
                "https://st.mangafunb.fun", "https://sw.mangafunb.fun",
                "https://sx.mangafunb.fun", "https://sy.mangafunb.fun",
                "https://sz.mangafunb.fun",
            )),
        profile("all.mangadex", "MangaDex", "1.2.0", 3, "all", true, 2_499_283L,
            "https://mangadex.org", "320efd108ed7656cb00dd5270f4e54c67f08fc9e3c2af0c362f57af987c2e1f3",
            requestOrigins = setOf("https://mangadex.org", "https://api.mangadex.org"),
            contentOrigins = setOf("https://uploads.mangadex.org"),
            // MangaDex's At-Home API deliberately rotates the reader node hostname. The host
            // policy accepts only the app-pinned *.mangadex.network family in the credential-free
            // content plane; API/catalogue requests remain exact-origin only.
            contentHostSuffixes = setOf(REVIEWED_MANGADEX_CONTENT_SUFFIX)),
        profile("zh.bilimanga.manga", "嗶哩漫畫（BiliManga）", "1.0.0", 1, "zh", true,
            7_289_707_411_592_168_382L, "https://www.bilimanga.net",
            "849db61372e4c7cf9d7ca689fbc427c1440dabd10c017a548c242175c651bbdd",
            runtimePermissions = loginPermissions,
            hostPermissions = setOf(PluginHostPermission.REQUEST_LOGIN_UI),
            events = loginRequired,
            // BiliManga serves catalogue/detail covers and reader AVIFs from its image CDN.
            // Keep this exact reviewed origin in the credential-free content plane; the source
            // page origin remains the only credential-bearing request origin.
            contentOrigins = setOf("https://i.motiezw.com")),
    )

    // Keep the currently published artifact reviewed until the repaired repository version is
    // distributed. Only this app-audited parser update receives the same narrow content policy.
    private val profiles = publishedProfiles + listOf(publishedProfiles.single { it.manifest.id == "zh.dm5" }.let { previous ->
        previous.copy(
            manifest = previous.manifest.copy(
                version = "1.4.1", versionCode = 6,
                signature = "88618e4c1e6df4a7955c40e5c24a879c6d04baf945d5a89fea25f2b27ca4e133",
            ),
            networkPolicy = previous.networkPolicy.copy(
                // Reader shards rotate opaque numbered hostnames. Pin only hosts observed in
                // authenticated public chapter responses; a general cdndm5.com suffix would be
                // broader authority than the reviewed artifact needs.
                contentOrigins = previous.networkPolicy.contentOrigins + setOf(
                    "https://manhua1041zjcdn63.cdndm5.com",
                "https://manhua1041zjcdn79.cdndm5.com",
                    "https://manhua1033zjcdn79.cdndm5.com",
                    "https://manhua1031zjcdn63.cdndm5.com",
                ),
            ),
        )
    }, publishedProfiles.single { it.manifest.id == "zh.bilimanga.manga" }.let { previous ->
        previous.copy(manifest = previous.manifest.copy(
            version = "1.0.1", versionCode = 2,
            signature = "0f4f4e8783d835cd5d5b4ee2734ecd219dc02da8df96aa71cce4bb1353f1f759",
        ))
    }, publishedProfiles.single { it.manifest.id == "zh.komiic" }.let { previous ->
        previous.copy(manifest = previous.manifest.copy(
            version = "1.2.2", versionCode = 6,
            signature = "ccdd5c25dc607f26a436377c15f9b4ab1c3ed41aad66e17bb758f23131186b9c",
        ))
    }, publishedProfiles.single { it.manifest.id == "zh.komiic" }.let { previous ->
        previous.copy(manifest = previous.manifest.copy(
            version = "1.2.1", versionCode = 5,
            signature = "6970799621bd0a557437524d6b41a2fb477f4e99fbd7789af7288b55cd2052ba",
        ))
    }, publishedProfiles.single { it.manifest.id == "zh.manhuagui" }.let { previous ->
        previous.copy(manifest = previous.manifest.copy(
            version = "1.1.8", versionCode = 10,
            signature = "fc6374218ed3c69a7e79823ef61a5f50b97556e29f6f6925ae145c192ab9660c",
        ))
    }, publishedProfiles.single { it.manifest.id == "zh.manhuagui" }.let { previous ->
        previous.copy(manifest = previous.manifest.copy(
            version = "1.1.7", versionCode = 9,
            signature = "75eb03ef359f5ba8aea594f5ec888c24ba045e7b012edd82949fb1a50c270d39",
        ))
    }, profile("zh.jinmantiantang", "禁漫天堂", "1.0.8", 9, "zh", true, 1_817_081L,
        "https://18comic.vip", "9836cc00dfb327357e7b22264efe8875ea49fc001a9a252176052a139e2ceb1c",
        runtimePermissions = ordinaryPermissions + setOf(
            PluginRuntimePermission.COOKIE_STORAGE, PluginRuntimePermission.BROWSER_CHALLENGE,
        ),
        contentOrigins = setOf("https://cdn-msp.18comic.vip", "https://cdn-msp2.18comic.vip"),
        webChallengeOrigins = setOf("https://18comic.vip"),
        // Managed Cloudflare verification embeds this exact origin. It is browser-subresource
        // only: no top-level navigation, plugin HTTP, cookie import, or credential grant.
        webChallengeSubresourceOrigins = setOf("https://18comic.vip", "https://challenges.cloudflare.com"),
        requiredWebChallengeCookieName = "cf_clearance",
    ), profile("zh.jinmantiantang", "禁漫天堂", "1.0.9", 10, "zh", true, 1_817_081L,
        "https://18comic.vip", "1cee1cf231775b8b622b855dc3d9e3269e52550201c88408c895537fec6880a8",
        runtimePermissions = ordinaryPermissions + setOf(
            PluginRuntimePermission.COOKIE_STORAGE, PluginRuntimePermission.BROWSER_CHALLENGE,
        ),
        // Public catalogue/detail responses rotate covers across these exact image-only hosts.
        // Keep the newly observed third shard out of request and credential origins.
        contentOrigins = setOf(
            "https://cdn-msp.18comic.vip",
            "https://cdn-msp2.18comic.vip",
            "https://cdn-msp3.18comic.vip",
        ),
        webChallengeOrigins = setOf("https://18comic.vip"),
        webChallengeSubresourceOrigins = setOf("https://18comic.vip", "https://challenges.cloudflare.com"),
        requiredWebChallengeCookieName = "cf_clearance",
    ), publishedProfiles.single { it.manifest.id == "zh.mycomic" }.let { previous ->
        previous.copy(manifest = previous.manifest.copy(
            version = "1.0.1", versionCode = 2,
            signature = "eee353857e13f2c07e7c72d9cd9be6eb6c32a55b1de927ec0985acd57795aa90",
        ))
    }, profile("zh.mycomic", "MyComic", "1.0.2", 3, "zh", true, 9_119_537_447_562_549_661L,
        "https://mycomic.com", "d79fbcd04e3398f57d73af021691fa368ae3d7702dc3ef62d00cc67144fd7eba",
        contentOrigins = setOf("https://biccam.com"),
        // Only the new exact artifact and its explicit grant receive browser authority.
        // Historical 1.0.0/1.0.1 installs retain their original, cookie-free permissions.
        runtimePermissions = ordinaryPermissions + setOf(
            PluginRuntimePermission.COOKIE_STORAGE, PluginRuntimePermission.BROWSER_CHALLENGE,
        ),
        webChallengeOrigins = setOf("https://mycomic.com"),
        webChallengeSubresourceOrigins = setOf("https://mycomic.com", "https://challenges.cloudflare.com"),
        requiredWebChallengeCookieName = "cf_clearance",
    ), publishedProfiles.single { it.manifest.id == "zh.baozimh" }.let { previous ->
        previous.copy(
            manifest = previous.manifest.copy(
                version = "1.0.7", versionCode = 8,
                signature = "79010b5571ca4079be744df68bb984df339b691c92a6bee0c61b3a2ba6372290",
            ),
            networkPolicy = previous.networkPolicy.copy(
                // The reviewed reader follows this exact public chapter origin. It must be
                // request-authorized for redirects, but must never inherit source credentials.
                requestOrigins = previous.networkPolicy.requestOrigins + "https://www.twmanga.com",
                contentOrigins = previous.networkPolicy.contentOrigins + "https://www.twmanga.com",
            ),
        )
    }, publishedProfiles.single { it.manifest.id == "zh.baozimh" }.let { previous ->
        previous.copy(
            manifest = previous.manifest.copy(
                version = "1.0.9", versionCode = 10,
                signature = "850c81d90644a3933fa0d6e5113c057c54d2c22f23b1c5c41dff83003c10f7a9",
            ),
            networkPolicy = previous.networkPolicy.copy(
                // The reviewed reader follows this exact public chapter origin. It must be
                // request-authorized for redirects, but must never inherit source credentials.
                requestOrigins = previous.networkPolicy.requestOrigins + "https://www.twmanga.com",
                contentOrigins = previous.networkPolicy.contentOrigins + "https://www.twmanga.com",
            ),
        )
    }, publishedProfiles.single { it.manifest.id == "zh.wnacg" }.let { previous ->
        previous.copy(manifest = previous.manifest.copy(
            version = "1.3.3", versionCode = 7,
            signature = "a6710148c297694ce8f2c2c2345eb68b3cb758aadc22334ab067c8bd06af0765",
        ), networkPolicy = previous.networkPolicy.copy(maxContentResponseBytes = 16 * 1024 * 1024))
    })

    /**
     * The currently reviewed publication served by the fixed local development repository.
     *
     * Keeping sizes beside the app-reviewed digests makes the local index a selector for compiled
     * policy, never an authority which can add a package, older revision, or privilege-bearing
     * manifest. Historical profiles above remain available only for already-installed official
     * packages and do not silently expand the local development repository.
     */
    private val reviewedLocalArtifactSizesBySha256 = mapOf(
        "564695e574d630d1336941adf2e5da11785ff83001c05229ab71e11a54bda485" to 32_531,
        "193d6d89e51fa252ad695f839fe54ef0ab0c154d3fb75718dae67fceaa874a8f" to 19_220,
        "1cee1cf231775b8b622b855dc3d9e3269e52550201c88408c895537fec6880a8" to 24_107,
        "79010b5571ca4079be744df68bb984df339b691c92a6bee0c61b3a2ba6372290" to 23_300,
        "850c81d90644a3933fa0d6e5113c057c54d2c22f23b1c5c41dff83003c10f7a9" to 23_123,
        "cd09553432d85389de169017aeeadd776f539183f13598c2259b4fec738cbdf1" to 35_431,
        "75eb03ef359f5ba8aea594f5ec888c24ba045e7b012edd82949fb1a50c270d39" to 29_153,
        "fc6374218ed3c69a7e79823ef61a5f50b97556e29f6f6925ae145c192ab9660c" to 32_025,
        "5a450bf1480675f9be5993188221e49fb350338217511eb8e61fe90e8b178731" to 10_676,
        "6970799621bd0a557437524d6b41a2fb477f4e99fbd7789af7288b55cd2052ba" to 11_172,
        "ccdd5c25dc607f26a436377c15f9b4ab1c3ed41aad66e17bb758f23131186b9c" to 11_711,
        "a6710148c297694ce8f2c2c2345eb68b3cb758aadc22334ab067c8bd06af0765" to 13_788,
        "d79fbcd04e3398f57d73af021691fa368ae3d7702dc3ef62d00cc67144fd7eba" to 23_990,
        "88618e4c1e6df4a7955c40e5c24a879c6d04baf945d5a89fea25f2b27ca4e133" to 22_580,
        "3d16db2fee1044211b83d11d3d9ab9daae4b52e0d98817527bc99028fb07baa1" to 14_587,
        "8da992d2eab8c5b1030b0c9c617e1447645ce8ac3442145760b63f28f62cd8cb" to 41_927,
        "320efd108ed7656cb00dd5270f4e54c67f08fc9e3c2af0c362f57af987c2e1f3" to 25_025,
        "849db61372e4c7cf9d7ca689fbc427c1440dabd10c017a548c242175c651bbdd" to 29_211,
        "0f4f4e8783d835cd5d5b4ee2734ecd219dc02da8df96aa71cce4bb1353f1f759" to 32_592,
    )

    fun match(
        stored: StoredPlugin,
        source: SourceIndexEntry?,
        reviewedLocalRepositoryPolicy: ReviewedLocalRepositoryPolicy = ReviewedLocalRepositoryPolicy.DISABLED,
    ): ReviewedOfficialShinsouSource? {
        // ExtensionRepositoryClient accepts surrounding whitespace and redundant trailing
        // slashes before applying the exact official-repository trust decision. PluginManager
        // retains the caller-facing repository value in installed metadata, so repeat only that
        // narrow canonicalization here. Do not normalize authorities, ports, encodings, or paths:
        // those variants never receive the official unsigned compatibility decision.
        val repositoryBaseUrl = stored.metadata.repositoryBaseUrl?.trim()?.trimEnd('/')
        if (repositoryBaseUrl != OFFICIAL_SHINSOU_REPOSITORY_BASE_URL &&
            !reviewedLocalRepositoryPolicy.admits(repositoryBaseUrl.orEmpty())
        ) return null
        if (reviewedLocalRepositoryPolicy.admits(repositoryBaseUrl.orEmpty()) &&
            stored.metadata.installedSha256 !in reviewedLocalArtifactSizesBySha256
        ) return null
        return matchExactArtifact(stored, source)
    }

    /**
     * Returns a compiled review only for one exact current local-repository row. Every field which
     * is persisted into [PluginManifest], plus selector-only fields, must equal app-owned policy.
     */
    fun matchReviewedRepositoryEntry(
        entry: PluginIndexEntry,
        reviewedLocalRepositoryPolicy: ReviewedLocalRepositoryPolicy,
        normalizedBaseUrl: String,
    ): ReviewedOfficialShinsouSource? {
        if (!reviewedLocalRepositoryPolicy.admits(normalizedBaseUrl)) return null
        val digest = entry.sha256 ?: return null
        val profile = profiles.singleOrNull { candidate ->
            candidate.manifest.signature == digest && candidate.matchesReviewedLocalEntry(entry)
        } ?: return null
        return profile.reviewedSource()
    }

    /** Exact current compiled digests which may be returned by a direct local script fetch. */
    fun reviewedLocalScriptDigests(scriptUrl: String): Set<String> {
        if (!scriptUrl.matches(Regex("plugins/[a-z0-9][a-z0-9._-]*\\.js"))) return emptySet()
        val packageId = scriptUrl.removePrefix("plugins/").removeSuffix(".js")
        return profiles.asSequence()
            .map(Profile::manifest)
            .filter { manifest ->
                manifest.id == packageId &&
                    manifest.script == "$packageId.js" &&
                    manifest.signature in reviewedLocalArtifactSizesBySha256
            }
            .map(PluginManifest::signature)
            .toSet()
    }

    /**
     * Exact-artifact, content-only policy for a package downloaded through any repository mirror.
     * The returned type deliberately has no execution-provenance minting method.
     */
    fun matchContent(
        stored: StoredPlugin,
        source: SourceIndexEntry?,
        reviewedLocalRepositoryPolicy: ReviewedLocalRepositoryPolicy = ReviewedLocalRepositoryPolicy.DISABLED,
    ): ReviewedOfficialShinsouContent? {
        val repositoryBaseUrl = stored.metadata.repositoryBaseUrl?.trim()?.trimEnd('/')
        // A fixed-local install is executable/content-authoritative only while its immutable
        // process opt-in remains enabled. Other exact byte mirrors retain the historical
        // credential-free content-only behavior and can never mint script provenance.
        if (isKnownReviewedLocalRepositoryBaseUrl(repositoryBaseUrl.orEmpty()) &&
            !reviewedLocalRepositoryPolicy.admits(repositoryBaseUrl.orEmpty())
        ) return null
        val exact = if (reviewedLocalRepositoryPolicy.admits(repositoryBaseUrl.orEmpty())) {
            match(stored, source, reviewedLocalRepositoryPolicy)
        } else {
            matchExactArtifact(stored, source)
        }
        return exact?.let {
            ReviewedOfficialShinsouContent(it.networkPolicy)
        }
    }

    private fun matchExactArtifact(
        stored: StoredPlugin,
        source: SourceIndexEntry?,
    ): ReviewedOfficialShinsouSource? {
        if (source == null) return null
        val profile = profiles.singleOrNull { it.manifest == stored.manifest } ?: return null
        val reviewedSource = profile.manifest.sources?.singleOrNull() ?: return null
        if (reviewedSource != source) return null
        if (stored.metadata.installedSha256 != profile.manifest.signature) return null
        if (Sha256.hex(stored.scriptBytes) != profile.manifest.signature) return null
        return profile.reviewedSource()
    }

    private fun Profile.reviewedSource(): ReviewedOfficialShinsouSource =
        ReviewedOfficialShinsouSource(
            networkPolicy, webChallengeOrigins, webChallengeSubresourceOrigins,
            requiredWebChallengeCookieName,
            // Exact reviewed Bika 1.0.12 can generate its own signing nonce. The current
            // website persists a token without a nonce; nonce is not proof of login.
            optionalWebChallengeStorageKeys = if (manifest.id == "zh.bika" &&
                manifest.signature == "cd09553432d85389de169017aeeadd776f539183f13598c2259b4fec738cbdf1"
            ) setOf("nonce") else emptySet(),
        )

    private fun Profile.matchesReviewedLocalEntry(entry: PluginIndexEntry): Boolean {
        val expectedSize = reviewedLocalArtifactSizesBySha256[manifest.signature] ?: return false
        val expectedCapabilities = ordinaryCapabilities +
            if (PluginHostPermission.REQUEST_LOGIN_UI in manifest.requestedHostPermissions) setOf("LOGIN")
            else emptySet()
        return entry.id == manifest.id &&
            entry.name == manifest.name &&
            entry.version == manifest.version &&
            entry.versionCode == manifest.versionCode &&
            entry.lang == manifest.lang &&
            (entry.nsfw == 1) == manifest.nsfw &&
            entry.scriptUrl == "plugins/${manifest.id}.js" &&
            entry.iconUrl == null && entry.description == null &&
            entry.sources == manifest.sources &&
            entry.sha256 == manifest.signature && entry.byteSize == expectedSize &&
            entry.minRuntimeVersion == manifest.minRuntimeVersion &&
            entry.type == null && entry.contentType == "manga" &&
            entry.contract == manifest.contract && entry.runtime == manifest.runtime &&
            entry.contentKinds == manifest.contentKinds.orEmpty() &&
            entry.capabilities == expectedCapabilities &&
            entry.sidecarUrl == manifest.sidecarUrl &&
            entry.systemEvents == manifest.systemEvents &&
            entry.requestedHostPermissions == manifest.requestedHostPermissions &&
            entry.runtimePermissions == manifest.runtimePermissions &&
            entry.installable && !entry.referenceOnly && !entry.legacyCompatibilityOnly
    }

    private fun profile(
        id: String,
        name: String,
        version: String,
        versionCode: Int,
        lang: String,
        nsfw: Boolean,
        sourceId: Long,
        baseUrl: String,
        sha256: String,
        runtimePermissions: Set<PluginRuntimePermission> = ordinaryPermissions,
        hostPermissions: Set<PluginHostPermission> = emptySet(),
        events: PluginSystemEventDeclaration = noEvents,
        requestOrigins: Set<String> = setOf(baseUrl),
        contentOrigins: Set<String> = emptySet(),
        contentHostSuffixes: Set<String> = emptySet(),
        browserSessionOrigins: Set<String> = emptySet(),
        webChallengeOrigins: Set<String> = browserSessionOrigins,
        webChallengeSubresourceOrigins: Set<String> = webChallengeOrigins,
        requiredWebChallengeCookieName: String? = null,
    ): Profile {
        val source = SourceIndexEntry(
            name = name,
            lang = lang,
            id = sourceId,
            baseUrl = baseUrl,
            contentKindsDeclared = false,
            browserSessionOrigins = browserSessionOrigins,
            originPolicyVersion = 2,
            legacyLongId = sourceId.toString(),
            canonicalSourceId = sourceId.toString(),
        )
        return Profile(
            manifest = PluginManifest(
                id = id,
                name = name,
                version = version,
                versionCode = versionCode,
                lang = lang,
                nsfw = nsfw,
                script = "$id.js",
                signature = sha256,
                sources = listOf(source),
                systemEvents = events,
                requestedHostPermissions = hostPermissions,
                runtimePermissions = runtimePermissions,
                contentKinds = setOf("IMAGE_SEQUENCE"),
                contract = "shinsou",
                runtime = "legacy-shinsou-adapter-v2",
                sidecarUrl = "sidecars/$id.json",
            ),
            networkPolicy = PluginNetworkPolicy(
                requestOrigins = requestOrigins,
                // Source headers and host-managed cookies are sent only to these exact reviewed
                // origins. PermissionFilteredPluginStorage independently denies cookie access to
                // artifacts whose reviewed runtime grant omits COOKIE_STORAGE.
                credentialOrigins = requestOrigins,
                contentOrigins = contentOrigins + requestOrigins,
                contentHostSuffixes = contentHostSuffixes,
                browserSessionOrigins = browserSessionOrigins,
            ),
            webChallengeOrigins = webChallengeOrigins,
            webChallengeSubresourceOrigins = webChallengeSubresourceOrigins,
            requiredWebChallengeCookieName = requiredWebChallengeCookieName,
        )
    }

    private data class Profile(
        val manifest: PluginManifest,
        val networkPolicy: PluginNetworkPolicy,
        val webChallengeOrigins: Set<String>,
        val webChallengeSubresourceOrigins: Set<String>,
        val requiredWebChallengeCookieName: String?,
    )
}

internal class ReviewedOfficialShinsouSource(
    val networkPolicy: PluginNetworkPolicy,
    /** Exact browser document origins reviewed in app code, separate from manifest API origins. */
    val webChallengeOrigins: Set<String>,
    val webChallengeSubresourceOrigins: Set<String> = webChallengeOrigins,
    val requiredWebChallengeCookieName: String? = null,
    val optionalWebChallengeStorageKeys: Set<String> = emptySet(),
) {
    fun provenance(
        artifact: PluginArtifactIdentity,
        sourceKey: SourceKey,
        evaluatedScript: String,
    ): InProcessScriptProvenance = InProcessScriptProvenance.reviewedArtifact(
        artifact = artifact,
        sourceKey = sourceKey,
        evaluatedScript = evaluatedScript,
    )
}

/** Credential-free content authority; never accepted by an in-process script runtime. */
internal class ReviewedOfficialShinsouContent(
    val networkPolicy: PluginNetworkPolicy,
)
