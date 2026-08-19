import Foundation

/// JavaScript DOM helper library injected into every JSContext.
/// Provides Jsoup-like `Jsoup.parse()`, `Document`, `Element`, `Elements` classes
/// that wrap the native bridge's handle-based DOM API.
enum JSDomLib {
    static let script: String = """
    // ===== Shinsou JS DOM Library =====
    // Wraps bridge.htmlParse / domSelect / domText / domAttr etc.
    // into a Jsoup-like API for easy porting of Android extensions.

    var Jsoup = {
        parse: function(html, baseUri) {
            var hid = baseUri ? bridge.htmlParseFragment(html, baseUri) : bridge.htmlParse(html);
            if (hid < 0) return null;
            return new Document(hid);
        }
    };

    function Document(hid) {
        this._hid = hid;
    }
    Document.prototype = Object.create(Element.prototype);
    Document.prototype.constructor = Document;

    function Element(hid) {
        this._hid = hid;
    }

    Element.prototype.select = function(css) {
        var ids = bridge.domSelect(this._hid, css);
        if (!ids || !ids.length) return new Elements([]);
        var elems = [];
        for (var i = 0; i < ids.length; i++) {
            elems.push(new Element(ids[i]));
        }
        return new Elements(elems);
    };

    Element.prototype.selectFirst = function(css) {
        var hid = bridge.domFirst(this._hid, css);
        if (hid < 0) return null;
        return new Element(hid);
    };

    Element.prototype.text = function() {
        return bridge.domText(this._hid);
    };

    Element.prototype.ownText = function() {
        return bridge.domOwnText(this._hid);
    };

    Element.prototype.html = function() {
        return bridge.domHtml(this._hid);
    };

    Element.prototype.outerHtml = function() {
        return bridge.domOuterHtml(this._hid);
    };

    Element.prototype.attr = function(name) {
        return bridge.domAttr(this._hid, name);
    };

    Element.prototype.hasAttr = function(name) {
        return bridge.domHasAttr(this._hid, name);
    };

    Element.prototype.absUrl = function(name) {
        return bridge.domAbsUrl(this._hid, name);
    };

    Element.prototype.tagName = function() {
        return bridge.domTagName(this._hid);
    };

    Element.prototype.className = function() {
        return bridge.domClassName(this._hid);
    };

    Element.prototype.id = function() {
        return bridge.domId(this._hid);
    };

    Element.prototype.children = function() {
        var ids = bridge.domChildren(this._hid);
        if (!ids || !ids.length) return new Elements([]);
        var elems = [];
        for (var i = 0; i < ids.length; i++) {
            elems.push(new Element(ids[i]));
        }
        return new Elements(elems);
    };

    Element.prototype.parent = function() {
        var hid = bridge.domParent(this._hid);
        if (hid < 0) return null;
        return new Element(hid);
    };

    Element.prototype.nextElementSibling = function() {
        var hid = bridge.domNextSibling(this._hid);
        if (hid < 0) return null;
        return new Element(hid);
    };

    Element.prototype.previousElementSibling = function() {
        var hid = bridge.domPrevSibling(this._hid);
        if (hid < 0) return null;
        return new Element(hid);
    };

    Element.prototype.remove = function() {
        bridge.domRemove(this._hid);
    };

    Element.prototype.release = function() {
        bridge.domRelease(this._hid);
    };

    // Convenience: element.getElementsByTag / getElementsByClass via select
    Element.prototype.getElementsByTag = function(tag) {
        return this.select(tag);
    };

    Element.prototype.getElementsByClass = function(cls) {
        return this.select("." + cls);
    };

    Element.prototype.getElementById = function(id) {
        return this.selectFirst("#" + id);
    };

    // toString
    Element.prototype.toString = function() {
        return this.outerHtml();
    };

    // ===== Elements (collection) =====

    function Elements(arr) {
        this._arr = arr || [];
        this.length = this._arr.length;
        // Also expose as indexed properties
        for (var i = 0; i < this._arr.length; i++) {
            this[i] = this._arr[i];
        }
    }

    Elements.prototype.get = function(index) {
        return this._arr[index] || null;
    };

    Elements.prototype.first = function() {
        return this._arr.length > 0 ? this._arr[0] : null;
    };

    Elements.prototype.last = function() {
        return this._arr.length > 0 ? this._arr[this._arr.length - 1] : null;
    };

    Elements.prototype.size = function() {
        return this._arr.length;
    };

    Elements.prototype.isEmpty = function() {
        return this._arr.length === 0;
    };

    Elements.prototype.text = function() {
        var texts = [];
        for (var i = 0; i < this._arr.length; i++) {
            var t = this._arr[i].text();
            if (t) texts.push(t);
        }
        return texts.join(" ");
    };

    Elements.prototype.attr = function(name) {
        if (this._arr.length > 0) return this._arr[0].attr(name);
        return "";
    };

    Elements.prototype.hasAttr = function(name) {
        if (this._arr.length > 0) return this._arr[0].hasAttr(name);
        return false;
    };

    Elements.prototype.html = function() {
        if (this._arr.length > 0) return this._arr[0].html();
        return "";
    };

    Elements.prototype.select = function(css) {
        var all = [];
        for (var i = 0; i < this._arr.length; i++) {
            var sub = this._arr[i].select(css);
            for (var j = 0; j < sub._arr.length; j++) {
                all.push(sub._arr[j]);
            }
        }
        return new Elements(all);
    };

    // Array-like iteration
    Elements.prototype.forEach = function(fn) {
        for (var i = 0; i < this._arr.length; i++) {
            fn(this._arr[i], i);
        }
    };

    Elements.prototype.map = function(fn) {
        var result = [];
        for (var i = 0; i < this._arr.length; i++) {
            result.push(fn(this._arr[i], i));
        }
        return result;
    };

    Elements.prototype.filter = function(fn) {
        var result = [];
        for (var i = 0; i < this._arr.length; i++) {
            if (fn(this._arr[i], i)) result.push(this._arr[i]);
        }
        return new Elements(result);
    };

    Elements.prototype.eachAttr = function(name) {
        return this.map(function(el) { return el.attr(name); });
    };

    Elements.prototype.eachText = function() {
        return this.map(function(el) { return el.text(); });
    };

    // Release all handles in this collection
    Elements.prototype.releaseAll = function() {
        for (var i = 0; i < this._arr.length; i++) {
            this._arr[i].release();
        }
    };

    // ===== Utility: Response.asJsoup() equivalent =====
    // Usage: var doc = fetchAndParse(url);  or  var doc = Jsoup.parse(html);

    function fetchAndParse(url, baseUri) {
        var html = bridge.httpGet(url);
        if (!html || html.error) return null;
        return Jsoup.parse(html, baseUri || url);
    }

    // ===== SManga / SChapter / Page helpers =====
    // These mirror Android's SManga.create() etc.

    var SManga = {
        create: function() {
            return {
                url: "",
                title: "",
                author: null,
                artist: null,
                description: null,
                genre: null,
                status: 0,
                thumbnailUrl: null,
                initialized: false
            };
        },
        UNKNOWN: 0,
        ONGOING: 1,
        COMPLETED: 2,
        LICENSED: 3,
        PUBLISHING_FINISHED: 4,
        CANCELLED: 5,
        ON_HIATUS: 6
    };

    var SChapter = {
        create: function() {
            return {
                url: "",
                name: "",
                scanlator: null,
                dateUpload: 0,
                chapterNumber: -1
            };
        }
    };

    function Page(index, url, imageUrl) {
        this.index = index || 0;
        this.url = url || "";
        this.imageUrl = imageUrl || null;
    }

    function MangasPage(mangas, hasNextPage) {
        this.mangas = mangas || [];
        this.hasNextPage = hasNextPage || false;
    }

    // ===== String extensions =====
    if (!String.prototype.substringAfter) {
        String.prototype.substringAfter = function(delimiter) {
            var idx = this.indexOf(delimiter);
            return idx >= 0 ? this.substring(idx + delimiter.length) : this;
        };
    }
    if (!String.prototype.substringBefore) {
        String.prototype.substringBefore = function(delimiter) {
            var idx = this.indexOf(delimiter);
            return idx >= 0 ? this.substring(0, idx) : this;
        };
    }
    if (!String.prototype.substringAfterLast) {
        String.prototype.substringAfterLast = function(delimiter) {
            var idx = this.lastIndexOf(delimiter);
            return idx >= 0 ? this.substring(idx + delimiter.length) : this;
        };
    }
    if (!String.prototype.substringBeforeLast) {
        String.prototype.substringBeforeLast = function(delimiter) {
            var idx = this.lastIndexOf(delimiter);
            return idx >= 0 ? this.substring(0, idx) : this;
        };
    }

    // ===== setUrlWithoutDomain helper =====
    function setUrlWithoutDomain(obj, url) {
        try {
            var u = new URL(url);
            obj.url = u.pathname + u.search;
        } catch(e) {
            // If url is already relative, use as-is
            obj.url = url;
        }
    }
    """
}
