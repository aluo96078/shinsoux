package dev.shinsou.kmp.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.content.ImageProgression
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Secure, browser-grade EPUB surface embedded directly in the Compose reader. */
@Composable
public expect fun EpubBrowserView(
    request: EpubRenderRequest,
    modifier: Modifier = Modifier,
    selectionRequestKey: Long = 0L,
    onLocatorChanged: (ReadingLocator.Epub) -> Unit = {},
    onSelectionChanged: (ReadingRange?) -> Unit = {},
    onError: (String) -> Unit = {},
)

/**
 * Browser-visible URL scope for one renderer surface.
 *
 * Android and iOS use the canonical publication root directly. Desktop supplies a random,
 * per-surface root and maps it back to the canonical resolver root. Keeping this mapping in common
 * code makes every native request callback apply the same exact-origin rule before touching a
 * resource body.
 */
internal class EpubBrowserUrlPolicy(
    val canonicalPublicationRootUrl: String,
    val browserPublicationRootUrl: String = canonicalPublicationRootUrl,
) {
    init {
        requireValidPublicationRoot(canonicalPublicationRootUrl)
        requireValidPublicationRoot(browserPublicationRootUrl)
    }

    fun canonicalUrl(browserUrl: String): String? {
        if (!browserUrl.isSafeBrowserUrl() || !browserUrl.startsWith(browserPublicationRootUrl)) return null
        val suffix = browserUrl.removePrefix(browserPublicationRootUrl)
        if (!suffix.isSafePublicationSuffix()) return null
        return canonicalPublicationRootUrl + suffix
    }

    fun browserUrl(canonicalUrl: String): String? {
        if (!canonicalUrl.isSafeBrowserUrl() || !canonicalUrl.startsWith(canonicalPublicationRootUrl)) return null
        val suffix = canonicalUrl.removePrefix(canonicalPublicationRootUrl)
        if (!suffix.isSafePublicationSuffix()) return null
        return browserPublicationRootUrl + suffix
    }

    fun allowsResource(browserUrl: String, resolver: EpubPublicationResourceResolver): Boolean {
        if (resolver.request.publicationRootUrl != canonicalPublicationRootUrl) return false
        val canonical = canonicalUrl(browserUrl) ?: return false
        return runCatching { resolver.contains(canonical) }.getOrDefault(false)
    }

    private companion object {
        fun requireValidPublicationRoot(value: String) {
            require(value.length in 12..MAX_BROWSER_URL_LENGTH && value.endsWith('/')) {
                "EPUB browser publication root is invalid"
            }
            val schemeSeparator = value.indexOf("://")
            require(schemeSeparator > 0 && schemeSeparator + 3 < value.length && value.isSafeBrowserUrl()) {
                "EPUB browser publication root is invalid"
            }
        }
    }
}

/**
 * Deterministic resolver ownership used by every native adapter.
 *
 * Reads and two-phase replacement share a small synchronous boundary. A staged resolver serves the
 * pending navigation while the committed resolver remains alive for rollback; [commit] closes the
 * previous cache and [rollback] closes only the staged cache.
 */
internal class EpubBrowserResolverSlot(
    initialResolver: EpubPublicationResourceResolver,
) {
    private val lock = SynchronousLock()
    private var committedResolver: EpubPublicationResourceResolver? = initialResolver
    private var pendingResolver: EpubPublicationResourceResolver? = null

    fun <T> read(block: (EpubPublicationResourceResolver) -> T): T? = lock.withLock {
        (pendingResolver ?: committedResolver)?.let(block)
    }

    fun stage(next: EpubPublicationResourceResolver) {
        lock.withLock {
            val committed = checkNotNull(committedResolver) { "EPUB browser resolver slot is closed" }
            require(committed.request.publicationRootUrl == next.request.publicationRootUrl) {
                "EPUB browser resolver slot cannot cross publication roots"
            }
            pendingResolver?.close()
            pendingResolver = next
        }
    }

    fun commit() {
        lock.withLock {
            val next = pendingResolver ?: return@withLock
            val previous = checkNotNull(committedResolver) { "EPUB browser resolver slot is closed" }
            pendingResolver = null
            committedResolver = next
            previous.close()
        }
    }

    fun rollback() {
        lock.withLock {
            pendingResolver?.close()
            pendingResolver = null
        }
    }

    fun replace(next: EpubPublicationResourceResolver) {
        stage(next)
        commit()
    }

    val hasPending: Boolean
        get() = lock.withLock { pendingResolver != null }

    val isClosed: Boolean
        get() = lock.withLock { committedResolver == null }

    fun committedRequest(): EpubRenderRequest? = lock.withLock {
        committedResolver?.request
    }

    fun pendingRequest(): EpubRenderRequest? = lock.withLock {
        pendingResolver?.request
    }

    fun close() {
        lock.withLock {
            pendingResolver?.close()
            pendingResolver = null
            committedResolver?.close()
            committedResolver = null
        }
    }
}

/** A request-time navigation admitted by [EpubBrowserUrlPolicy]. */
internal data class EpubBrowserNavigationTarget(
    val browserUrl: String,
    val canonicalUrl: String,
    val documentIndex: Int,
    val resourceHref: String,
    val fragment: String?,
    val request: EpubRenderRequest,
) {
    val hasExplicitAnchor: Boolean get() = fragment != null
}

internal data class EpubBrowserDocumentLoad(
    val target: EpubBrowserNavigationTarget,
    val restoreInitialLocator: Boolean,
)

internal data class EpubBrowserDocumentRollback(
    val request: EpubRenderRequest,
    val browserUrl: String,
)

/**
 * Owns the active spine identity independently of Compose's last host request.
 *
 * A link can move WebKit to another spine resource before Compose creates the next render request.
 * This state stages the next locator identity at request time and commits it only after the native
 * browser reports a successful load. Viewport sampling pauses while staged, and a failed load keeps
 * the previous document identity. Explicit `#fragment` navigations consume the pending host restore
 * and are therefore never overwritten by it.
 */
internal class EpubBrowserDocumentState(
    initialRequest: EpubRenderRequest,
    browserPublicationRootUrl: String = initialRequest.publicationRootUrl,
) {
    private var committedAuthorityRequest: EpubRenderRequest = initialRequest
    private var committedRequestsByDocument: MutableMap<Int, EpubRenderRequest> =
        linkedMapOf(initialRequest.documentIndex to initialRequest)
    private var pendingHostRequest: EpubRenderRequest? = null
    private var pendingRequestsByDocument: MutableMap<Int, EpubRenderRequest>? = null
    private var pendingInitialRestoreUrl: String? = initialRequest.documentUrl

    var urlPolicy: EpubBrowserUrlPolicy = EpubBrowserUrlPolicy(
        canonicalPublicationRootUrl = initialRequest.publicationRootUrl,
        browserPublicationRootUrl = browserPublicationRootUrl,
    )
        private set

    var committedRequest: EpubRenderRequest = initialRequest
        private set

    var pendingTarget: EpubBrowserNavigationTarget? = null
        private set

    var pendingLoadGeneration: Long? = null
        private set

    val activeRequest: EpubRenderRequest get() = committedRequest
    val canSampleViewport: Boolean get() = pendingTarget == null

    val initialBrowserDocumentUrl: String = requireNotNull(urlPolicy.browserUrl(initialRequest.documentUrl))
    private var committedBrowserDocumentUrl: String = initialBrowserDocumentUrl

    fun updateHostRequest(
        request: EpubRenderRequest,
        browserPublicationRootUrl: String = urlPolicy.browserPublicationRootUrl,
    ): String {
        pendingHostRequest = request
        pendingRequestsByDocument = linkedMapOf(request.documentIndex to request)
        pendingInitialRestoreUrl = request.documentUrl
        urlPolicy = EpubBrowserUrlPolicy(request.publicationRootUrl, browserPublicationRootUrl)
        val browserUrl = requireNotNull(urlPolicy.browserUrl(request.documentUrl))
        pendingTarget = requireNotNull(navigationTarget(browserUrl, request, pendingRequestsByDocument!!))
        pendingLoadGeneration = nextLoadGeneration()
        return browserUrl
    }

    /** Main-frame policy hook. A null result must be cancelled by the native adapter. */
    fun navigationRequested(browserUrl: String): EpubBrowserNavigationTarget? {
        val target = navigationTarget(browserUrl) ?: return null
        val existing = pendingTarget
        val replacesPendingTarget = existing == null || !existing.matches(target)
        val preservesInitialRestore = !target.hasExplicitAnchor &&
            epubBrowserUrlWithoutLoadGeneration(target.canonicalUrl) == pendingInitialRestoreUrl
        if (!preservesInitialRestore) {
            pendingInitialRestoreUrl = null
        }
        if (replacesPendingTarget) {
            pendingLoadGeneration = nextLoadGeneration()
            pendingTarget = target
        }
        return target
    }

    /** Load-completion hook used to decide whether the saved locator may still be restored. */
    fun documentLoaded(browserUrl: String, generation: Long): EpubBrowserDocumentLoad? {
        if (pendingLoadGeneration != generation) return null
        val expected = pendingTarget ?: return null
        val resolved = navigationTarget(browserUrl) ?: return null
        if (!expected.matches(resolved)) return null
        val target = expected
        val restore = !target.hasExplicitAnchor &&
            epubBrowserUrlWithoutLoadGeneration(target.canonicalUrl) == pendingInitialRestoreUrl
        pendingHostRequest?.let { committedAuthorityRequest = it }
        pendingRequestsByDocument?.let { committedRequestsByDocument = it }
        committedRequest = target.request
        committedBrowserDocumentUrl = target.browserUrl
        pendingHostRequest = null
        pendingRequestsByDocument = null
        pendingTarget = null
        pendingLoadGeneration = null
        pendingInitialRestoreUrl = null
        return EpubBrowserDocumentLoad(target, restore)
    }

    fun sameDocumentNavigationLoaded(browserUrl: String, generation: Long): EpubBrowserDocumentLoad? {
        val target = navigationTarget(browserUrl) ?: return null
        if (!target.hasExplicitAnchor || target.request.documentIndex != committedRequest.documentIndex ||
            target.resourceHref != committedRequest.document.href
        ) return null
        return documentLoaded(browserUrl, generation)
    }

    fun ownsPendingLoad(generation: Long): Boolean =
        pendingLoadGeneration == generation && pendingTarget != null

    fun documentLoadFailed(generation: Long): EpubBrowserDocumentRollback? {
        if (!ownsPendingLoad(generation)) return null
        pendingHostRequest = null
        pendingRequestsByDocument = null
        pendingTarget = null
        pendingLoadGeneration = null
        pendingInitialRestoreUrl = null
        return EpubBrowserDocumentRollback(committedRequest, committedBrowserDocumentUrl)
    }

    fun canonicalResourceUrl(browserUrl: String, resolver: EpubPublicationResourceResolver): String? {
        val canonical = urlPolicy.canonicalUrl(browserUrl) ?: return null
        return canonical.takeIf { runCatching { resolver.contains(it) }.getOrDefault(false) }
    }

    private fun navigationTarget(browserUrl: String): EpubBrowserNavigationTarget? {
        val pendingRequest = pendingHostRequest
        val pendingRequests = pendingRequestsByDocument
        if (pendingRequest != null && pendingRequests != null) {
            return navigationTarget(browserUrl, pendingRequest, pendingRequests)
        }
        return navigationTarget(browserUrl, committedAuthorityRequest, committedRequestsByDocument)
    }

    private fun navigationTarget(
        browserUrl: String,
        authorityRequest: EpubRenderRequest,
        requestsByDocument: MutableMap<Int, EpubRenderRequest>,
    ): EpubBrowserNavigationTarget? {
        val canonical = urlPolicy.canonicalUrl(browserUrl) ?: return null
        val resource = authorityRequest.resourceByPublicationUrl(canonical) ?: return null
        val documentIndex = authorityRequest.navigation.representation.documents.indexOfFirst { document ->
            document.resourceId == resource.resourceId && document.href == resource.href
        }.takeIf { it >= 0 } ?: return null
        return EpubBrowserNavigationTarget(
            browserUrl = requireNotNull(urlPolicy.browserUrl(canonical)),
            canonicalUrl = canonical,
            documentIndex = documentIndex,
            resourceHref = resource.href,
            fragment = canonical.substringAfter('#', missingDelimiterValue = "").let { fragment ->
                fragment.takeIf { '#' in canonical }
            },
            request = requestForDocument(authorityRequest, requestsByDocument, documentIndex),
        )
    }

    private fun requestForDocument(
        authorityRequest: EpubRenderRequest,
        requestsByDocument: MutableMap<Int, EpubRenderRequest>,
        documentIndex: Int,
    ): EpubRenderRequest {
        requestsByDocument[documentIndex]?.let { return it }
        val request = EpubRenderRequest(
            navigation = authorityRequest.navigation,
            documentIndex = documentIndex,
            initialLocator = authorityRequest.navigation.locatorAt(documentIndex),
            publisherResources = authorityRequest.publisherResources,
            userStyleSheets = authorityRequest.userStyleSheets,
            securityPolicy = authorityRequest.securityPolicy,
        )
        if (requestsByDocument.size >= MAX_BROWSER_DOCUMENT_REQUEST_CACHE_ENTRIES) {
            requestsByDocument.keys.firstOrNull { it != authorityRequest.documentIndex }?.let { oldest ->
                requestsByDocument.remove(oldest)
            }
        }
        requestsByDocument[documentIndex] = request
        return request
    }

    private fun EpubBrowserNavigationTarget.matches(other: EpubBrowserNavigationTarget): Boolean =
        documentIndex == other.documentIndex && resourceHref == other.resourceHref &&
            fragment == other.fragment

    private var lastLoadGeneration: Long = 0L

    private fun nextLoadGeneration(): Long {
        lastLoadGeneration = if (lastLoadGeneration == Long.MAX_VALUE) 1L else lastLoadGeneration + 1L
        return lastLoadGeneration
    }
}

internal fun epubBrowserUrlWithLoadGeneration(browserUrl: String, generation: Long): String {
    require(generation > 0L) { "EPUB browser load generation must be positive" }
    val fragmentIndex = browserUrl.indexOf('#')
    val withoutFragment = if (fragmentIndex >= 0) browserUrl.substring(0, fragmentIndex) else browserUrl
    val fragment = if (fragmentIndex >= 0) browserUrl.substring(fragmentIndex) else ""
    val queryIndex = withoutFragment.indexOf('?')
    val path = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
    val existing = if (queryIndex >= 0) withoutFragment.substring(queryIndex + 1) else ""
    val parameters = existing.split('&')
        .filter { it.isNotEmpty() && it.substringBefore('=') != EPUB_BROWSER_LOAD_GENERATION_PARAMETER }
    val query = (parameters + "$EPUB_BROWSER_LOAD_GENERATION_PARAMETER=$generation").joinToString("&")
    return "$path?$query$fragment"
}

internal fun epubBrowserLoadGeneration(browserUrl: String): Long? {
    val query = browserUrl.substringBefore('#').substringAfter('?', missingDelimiterValue = "")
    if (query.isEmpty()) return null
    val values = query.split('&').mapNotNull { parameter ->
        val name = parameter.substringBefore('=')
        if (name != EPUB_BROWSER_LOAD_GENERATION_PARAMETER || '=' !in parameter) return@mapNotNull null
        parameter.substringAfter('=').toLongOrNull()?.takeIf { it > 0L }
    }
    return values.singleOrNull()
}

internal fun epubBrowserUrlWithoutLoadGeneration(browserUrl: String): String {
    val fragmentIndex = browserUrl.indexOf('#')
    val withoutFragment = if (fragmentIndex >= 0) browserUrl.substring(0, fragmentIndex) else browserUrl
    val fragment = if (fragmentIndex >= 0) browserUrl.substring(fragmentIndex) else ""
    val queryIndex = withoutFragment.indexOf('?')
    if (queryIndex < 0) return browserUrl
    val path = withoutFragment.substring(0, queryIndex)
    val parameters = withoutFragment.substring(queryIndex + 1).split('&')
        .filter { it.isNotEmpty() && it.substringBefore('=') != EPUB_BROWSER_LOAD_GENERATION_PARAMETER }
    val query = parameters.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
    return "$path$query$fragment"
}

@Serializable
internal enum class EpubBrowserScrollAxis {
    HORIZONTAL,
    VERTICAL,
}

@Serializable
internal enum class EpubBrowserScrollDirection {
    FORWARD,
    REVERSE,
}

/** JSON contract returned by the host-authored viewport JavaScript on every platform. */
@Serializable
internal data class EpubBrowserViewportSnapshot(
    val progression: Double,
    val axis: EpubBrowserScrollAxis,
    val direction: EpubBrowserScrollDirection,
    val writingMode: String,
    val fixedLayout: Boolean,
    val anchorCfi: String? = null,
) {
    init {
        require(progression.isFinite() && progression in 0.0..1.0) {
            "EPUB browser viewport progression is invalid"
        }
        require(writingMode.length <= MAX_WRITING_MODE_LENGTH &&
            writingMode.none(Char::isISOControl)
        ) { "EPUB browser writing mode is invalid" }
        require(anchorCfi == null ||
            (anchorCfi.length <= MAX_ANCHOR_CFI_LENGTH && ANCHOR_CFI.matches(anchorCfi))
        ) {
            "EPUB browser anchor CFI is invalid"
        }
    }
}

internal fun decodeEpubBrowserViewport(json: String): EpubBrowserViewportSnapshot? {
    if (json.length > MAX_VIEWPORT_SNAPSHOT_JSON_LENGTH) return null
    return runCatching {
        EpubBrowserContractJson.decodeFromString(EpubBrowserViewportSnapshot.serializer(), json)
    }.getOrNull()
}

internal fun EpubViewportLocatorCoalescer.offerViewport(
    viewport: EpubBrowserViewportSnapshot,
    nowMillis: Long,
    force: Boolean = false,
): ReadingLocator.Epub? = offer(viewport.progression, nowMillis, force)?.let { locator ->
    viewport.anchorCfi?.let { anchorCfi -> locator.copy(cfi = anchorCfi) } ?: locator
}

/** Host-authored script; publisher scripts remain disabled by the response CSP. */
internal fun epubBrowserViewportScript(request: EpubRenderRequest): String =
    epubBrowserScript(request) {
        "return JSON.stringify(shinsouSnapshot(shinsouMetrics()));"
    }

/** Restores along the real CSS scroll axis and returns the resulting portable viewport snapshot. */
internal fun epubBrowserRestoreScript(request: EpubRenderRequest): String =
    epubBrowserScript(request) {
        val progression = request.initialDocumentProgression
        """
        var m=shinsouMetrics();
        var p=$progression;
        var e=m.element;
        if(m.axis==='HORIZONTAL'){
          var logical=p*m.range;
          var normalized=m.rtl?m.range-logical:logical;
          var raw=normalized;
          if(m.rtl&&m.rtlType==='negative')raw=normalized-m.range;
          else if(m.rtl&&m.rtlType==='reverse')raw=m.range-normalized;
          e.scrollLeft=raw;
        }else{
          e.scrollTop=p*m.range;
        }
        return JSON.stringify(shinsouSnapshot(shinsouMetrics()));
        """.trimIndent()
    }

/** Selection and viewport progression deliberately share the exact same metric function. */
internal fun epubBrowserSelectionScript(request: EpubRenderRequest): String =
    epubBrowserScript(request) {
        """
        var m=shinsouMetrics();
        var s=window.getSelection?String(window.getSelection()):'';
        return JSON.stringify({progression:m.progression,text:s.slice(0,256)});
        """.trimIndent()
    }

/**
 * Capture-phase guard installed by every adapter after a document commits. Native request hooks
 * remain authoritative; this guard prevents an external anchor from starting a Desktop request in
 * engines without a public URL-loading interceptor.
 */
internal fun epubBrowserNavigationGuardScript(browserPublicationRootUrl: String): String {
    val root = browserPublicationRootUrl.toJavaScriptStringLiteral()
    return """
        (function(){
          'use strict';
          var root=$root;
          if(window.__shinsouEpubNavigationRoot===root)return true;
          window.__shinsouEpubNavigationRoot=root;
          function allowed(raw){
            try{
              var u=new URL(raw,document.baseURI);
              return u.href.indexOf(root)===0;
            }catch(_){return false;}
          }
          function linkFrom(node){
            while(node&&node!==document){
              var name=(node.localName||'').toLowerCase();
              if(name==='a'||name==='area')return node;
              node=node.parentNode;
            }
            return null;
          }
          function guard(event){
            var link=linkFrom(event.target);
            if(!link)return;
            var target=(link.getAttribute('target')||'').toLowerCase();
            var unsafeTarget=target&&target!=='_self';
            var unsafeSideEffect=link.hasAttribute('download')||link.hasAttribute('ping');
            if(unsafeTarget||unsafeSideEffect||!allowed(link.href||link.getAttribute('href')||'')){
              event.preventDefault();
              event.stopImmediatePropagation();
            }
          }
          document.addEventListener('click',guard,true);
          document.addEventListener('auxclick',guard,true);
          document.addEventListener('submit',function(event){
            event.preventDefault();
            event.stopImmediatePropagation();
          },true);
          return true;
        })()
    """.trimIndent()
}

private data class EpubBrowserLayoutHints(
    val fixedLayout: Boolean,
    val rightToLeft: Boolean,
)

private fun EpubRenderRequest.browserLayoutHints(): EpubBrowserLayoutHints {
    val document = navigation.representation.documents[documentIndex]
    val resourceProperties = navigation.representation.packageGraph.resources
        .firstOrNull { it.id == document.resourceId && it.href == document.href }
        ?.properties
        .orEmpty()
    val itemLayoutOverride = browserFixedLayoutOverride(
        layout = document.rendition?.layout,
        properties = document.properties,
    )
    val manifestResourceFallback = browserFixedLayoutOverride(
        layout = null,
        properties = resourceProperties,
    )
    val packageLayouts = navigation.representation.packageGraph.renditions
        .mapNotNull { rendition -> rendition.layout?.lowercase() }
    val packageFixedLayout = when {
        packageLayouts.any { it in REFLOWABLE_LAYOUT_VALUES } -> false
        packageLayouts.any { it in FIXED_LAYOUT_VALUES } -> true
        else -> false
    }
    return EpubBrowserLayoutHints(
        fixedLayout = itemLayoutOverride ?: manifestResourceFallback ?: packageFixedLayout,
        rightToLeft = document.pageProgression == ImageProgression.RIGHT_TO_LEFT,
    )
}

private fun browserFixedLayoutOverride(
    layout: String?,
    properties: Set<String>,
): Boolean? {
    val tokens = buildList {
        layout?.let { add(it) }
        addAll(properties)
    }.map(String::lowercase)
    return when {
        tokens.any { it in REFLOWABLE_LAYOUT_VALUES || it in REFLOWABLE_LAYOUT_PROPERTIES } -> false
        tokens.any { it in FIXED_LAYOUT_VALUES || it in FIXED_LAYOUT_PROPERTIES } -> true
        else -> null
    }
}

private inline fun epubBrowserScript(
    request: EpubRenderRequest,
    result: () -> String,
): String {
    val hints = request.browserLayoutHints()
    val packageStep = (request.documentIndex + 1) * 2
    return """
        (function(){
          'use strict';
          var shinsouFixed=${hints.fixedLayout};
          var shinsouPageRtl=${hints.rightToLeft};
          var shinsouPackageStep=$packageStep;
          var shinsouMaxAnchorDepth=$MAX_ANCHOR_DOM_DEPTH;
          var shinsouMaxAnchorCfiLength=$MAX_ANCHOR_CFI_LENGTH;
          var shinsouMaxFragmentLength=$MAX_ANCHOR_FRAGMENT_LENGTH;
          function shinsouRtlType(){
            var cached=window.__shinsouEpubRtlScrollType;
            if(cached==='default'||cached==='negative'||cached==='reverse')return cached;
            var host=document.body||document.documentElement;
            if(!host)return 'negative';
            var outer=document.createElement('div');
            var inner=document.createElement('div');
            outer.dir='rtl';
            outer.style.cssText='position:absolute!important;left:-10000px!important;top:-10000px!important;' +
              'width:4px!important;height:1px!important;overflow:scroll!important;visibility:hidden!important;';
            inner.style.cssText='width:8px!important;height:1px!important;';
            outer.appendChild(inner);host.appendChild(outer);
            var type;
            if(outer.scrollLeft>0)type='default';
            else{outer.scrollLeft=1;type=outer.scrollLeft===0?'negative':'reverse';}
            host.removeChild(outer);
            window.__shinsouEpubRtlScrollType=type;
            return window.__shinsouEpubRtlScrollType;
          }
          function shinsouMetrics(){
            var de=document.documentElement;
            var body=document.body;
            var e=document.scrollingElement||de||body;
            var deStyle=de?window.getComputedStyle(de):null;
            var bodyStyle=body?window.getComputedStyle(body):null;
            var writing=((bodyStyle&&(bodyStyle.writingMode||bodyStyle.webkitWritingMode))||
              (deStyle&&(deStyle.writingMode||deStyle.webkitWritingMode))||'horizontal-tb').toLowerCase();
            if(writing==='horizontal-tb'&&deStyle){
              writing=(deStyle.writingMode||deStyle.webkitWritingMode||writing).toLowerCase();
            }
            var cssDirection=((bodyStyle&&bodyStyle.direction)||(deStyle&&deStyle.direction)||'ltr').toLowerCase();
            var width=Math.max(e?e.scrollWidth:0,de?de.scrollWidth:0,body?body.scrollWidth:0);
            var height=Math.max(e?e.scrollHeight:0,de?de.scrollHeight:0,body?body.scrollHeight:0);
            var viewportWidth=Math.max(1,e?e.clientWidth:0,window.innerWidth||0);
            var viewportHeight=Math.max(1,e?e.clientHeight:0,window.innerHeight||0);
            var xRange=Math.max(0,width-viewportWidth);
            var yRange=Math.max(0,height-viewportHeight);
            var verticalWriting=writing.indexOf('vertical-')===0||writing.indexOf('sideways-')===0;
            var horizontal=verticalWriting||(xRange>0.5&&(shinsouFixed||yRange<=0.5||xRange>=yRange));
            var rtl=horizontal&&(shinsouPageRtl||cssDirection==='rtl'||/-rl$/.test(writing));
            var rtlType=rtl?shinsouRtlType():'default';
            var range=horizontal?xRange:yRange;
            var raw=horizontal?(e?e.scrollLeft:window.scrollX||0):(e?e.scrollTop:window.scrollY||0);
            var logical=raw;
            if(horizontal&&rtl){
              if(rtlType==='negative')logical=-raw;
              else if(rtlType==='reverse')logical=raw;
              else logical=range-raw;
            }
            var progression=range<=0?0:Math.max(0,Math.min(1,logical/range));
            return {progression:progression,axis:horizontal?'HORIZONTAL':'VERTICAL',
              direction:rtl?'REVERSE':'FORWARD',writingMode:writing.slice(0,32),
              fixedLayout:shinsouFixed,anchorCfi:shinsouAnchorCfi(horizontal),
              element:e,range:range,rtl:rtl,rtlType:rtlType};
          }
          function shinsouAnchorCfi(horizontal){
            if(!window.location.hash||window.location.hash.length<2||
              window.location.hash.length>shinsouMaxFragmentLength)return null;
            var id;
            try{id=decodeURIComponent(window.location.hash.slice(1));}catch(_){return null;}
            var target=document.getElementById(id);
            if(!target||!target.getBoundingClientRect)return null;
            var rect=target.getBoundingClientRect();
            var visible=horizontal?(rect.right>=0&&rect.left<=window.innerWidth):
              (rect.bottom>=0&&rect.top<=window.innerHeight);
            if(!visible)return null;
            var steps=[];
            var node=target;
            var depth=0;
            while(node&&node!==document.documentElement){
              depth++;
              if(depth>shinsouMaxAnchorDepth)return null;
              var parent=node.parentElement;
              if(!parent)return null;
              var ordinal=0;
              for(var index=0;index<parent.children.length;index++){
                if(parent.children[index]===node){ordinal=index+1;break;}
              }
              if(ordinal===0)return null;
              steps.unshift('/'+(ordinal*2));
              node=parent;
            }
            if(node!==document.documentElement||steps.length===0)return null;
            var cfi='epubcfi(/6/'+shinsouPackageStep+'!'+steps.join('')+')';
            return cfi.length<=shinsouMaxAnchorCfiLength?cfi:null;
          }
          function shinsouSnapshot(metrics){
            return {progression:metrics.progression,axis:metrics.axis,direction:metrics.direction,
              writingMode:metrics.writingMode,fixedLayout:metrics.fixedLayout,
              anchorCfi:metrics.anchorCfi};
          }
          ${result()}
        })()
    """.trimIndent()
}

private fun String.toJavaScriptStringLiteral(): String = buildString(length + 2) {
    append('\'')
    this@toJavaScriptStringLiteral.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '\'' -> append("\\'")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> append(character)
        }
    }
    append('\'')
}

private fun String.isSafeBrowserUrl(): Boolean =
    length <= MAX_BROWSER_URL_LENGTH && isNotBlank() && none { it.isISOControl() || it == '\\' }

private fun String.isSafePublicationSuffix(): Boolean {
    if (isEmpty() || startsWith('/')) return false
    val path = substringBefore('#').substringBefore('?')
    if (path.isEmpty()) return false
    return path.split('/').none { rawSegment ->
        val segment = rawSegment.lowercase()
        segment == "." || segment == ".." || segment == "%2e" || segment == "%2e%2e" ||
            segment == ".%2e" || segment == "%2e." || "%2f" in segment || "%5c" in segment
    }
}

private val EpubBrowserContractJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

private val FIXED_LAYOUT_VALUES: Set<String> = setOf("pre-paginated", "fixed")
private val REFLOWABLE_LAYOUT_VALUES: Set<String> = setOf("reflowable")
private val FIXED_LAYOUT_PROPERTIES: Set<String> = setOf(
    "rendition:layout-pre-paginated",
    "rendition-layout-pre-paginated",
    "pre-paginated",
)
private val REFLOWABLE_LAYOUT_PROPERTIES: Set<String> = setOf(
    "rendition:layout-reflowable",
    "rendition-layout-reflowable",
    "reflowable",
)
private const val MAX_BROWSER_URL_LENGTH: Int = 8_192
private const val MAX_BROWSER_DOCUMENT_REQUEST_CACHE_ENTRIES: Int = 16
private const val EPUB_BROWSER_LOAD_GENERATION_PARAMETER: String = "__shinsou_epub_load"
private const val MAX_WRITING_MODE_LENGTH: Int = 32
private const val MAX_ANCHOR_DOM_DEPTH: Int = 64
private const val MAX_ANCHOR_CFI_LENGTH: Int = 1_024
private const val MAX_ANCHOR_FRAGMENT_LENGTH: Int = 1_024
private const val MAX_VIEWPORT_SNAPSHOT_JSON_LENGTH: Int = 2_048
private val ANCHOR_CFI: Regex = Regex("epubcfi\\(/6/[1-9][0-9]*!(?:/[1-9][0-9]*)+\\)")
