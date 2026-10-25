package dev.shinsou.kmp.plugin

import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WNACG's list contract: collect fields, release DOM resources, then return plain models. */
internal suspend fun verifyDomReleaseListCompatibility(factory: ScriptPluginRuntimeFactory) {
    val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
    val runtime = factory.create(
        script = """
            var source={baseUrl:'https://source.example',getPopularManga:function(){
                var doc=Jsoup.parse('<div><a href="/photos-index-aid-42.html" title="Gallery &amp; test"><img data-original="//images.example/cover.webp"></a></div><a class="next">Next</a>',this.baseUrl);
                var mangas=[];
                doc.select("a[href*='photos-index-aid-']").forEach(function(link){
                    var manga=SManga.create();
                    manga.url=link.attr('href');manga.title=link.attr('title');
                    manga.thumbnailUrl='https:'+link.selectFirst('img').attr('data-original');
                    mangas.push(manga);
                });
                var hasNext=doc.selectFirst('a.next, a:contains(Next)')!==null;
                bridge.domReleaseAll();
                bridge.domReleaseAll();
                return new MangasPage(mangas,hasNext);
            }};
        """.trimIndent(),
        manifest = PluginManifest(
            "dom-release.fixture", "DOM release fixture", "1.0.0", 1, "all",
            script = "fixture.js", signature = "",
            sources = listOf(SourceIndexEntry("Fixture", "all", 399L, "https://source.example")),
        ),
        environment = ScriptPluginEnvironment(
            PluginNetworkClient(PluginHttpTransport { error("Offline fixture must not request the network") }, storage),
            storage,
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
        ),
    )
    try {
        repeat(2) {
            val result = runtime.getPopularManga(0)
            val manga = result.mangas.single()
            assertEquals("/photos-index-aid-42.html", manga.url)
            assertEquals("Gallery & test", manga.title)
            assertEquals("https://images.example/cover.webp", manga.thumbnailUrl)
            assertTrue(result.hasNextPage)
        }
    } finally {
        runtime.close()
    }
}
