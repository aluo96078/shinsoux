package dev.shinsou.kmp.plugin

/**
 * Browser-less HTML/selector compatibility layer for JavaScriptCore. It intentionally implements
 * the Jsoup surface used by Shinsou's synchronous repository plugins rather than exposing WebKit.
 */
internal const val IOS_JAVASCRIPTCORE_BOOTSTRAP: String = """
(function(global){
  'use strict';
  function nativeCall(method,args){
    var encoded=__shinsouBridge(String(method),JSON.stringify(args||[]));
    try{return JSON.parse(String(encoded));}catch(e){return null;}
  }
  global.bridge={
    httpGet:function(url){return nativeCall('httpGet',[String(url)]);},
    httpGetWithHeaders:function(url,headers){return nativeCall('httpGetWithHeaders',[String(url),headers||{}]);},
    httpPost:function(url,body,headers){return nativeCall('httpPost',[String(url),String(body||''),headers||{}]);},
    log:function(sourceOrMessage,message){nativeCall('log',[String(message===undefined?sourceOrMessage:message)]);},
    getPreference:function(key){return nativeCall('getPreference',[String(key)]);},
    setPreference:function(key,value){nativeCall('setPreference',[String(key),String(value)]);},
    getCredentialUsername:function(){return nativeCall('getCredentialUsername',[]);},
    getCredentialPassword:function(){return nativeCall('getCredentialPassword',[]);},
    setCredential:function(username,password){nativeCall('setCredential',[String(username),String(password)]);},
    clearCredential:function(){nativeCall('clearCredential',[]);},
    hasCredential:function(){return !!nativeCall('hasCredential',[]);},
    requestLogin:function(reason){return !!nativeCall('requestLogin',reason==null?[]:[String(reason)]);},
    getCookie:function(name,url){return nativeCall('getCookie',[String(name),String(url)]);},
    getCookies:function(url){return nativeCall('getCookies',[String(url)])||{};},
    setCookie:function(name,value,domain,path,seconds){return !!nativeCall('setCookie',[String(name),String(value),String(domain),String(path||'/'),Number(seconds||0)]);},
    deleteCookie:function(name,domain){nativeCall('deleteCookie',[String(name),String(domain)]);},
    clearCookies:function(){nativeCall('clearCookies',[]);},
    domReleaseAll:function(){nativeCall('domReleaseAll',[]);},
    domRelease:function(id){nativeCall('domRelease',[Number(id||0)]);},
    parseHtml:function(html,selector){var doc=parseHtml(String(html),'');return doc.select(String(selector)).map(function(e){return{text:e.text(),html:e.html(),outerHtml:e.outerHtml(),attr_href:e.attr('href'),attr_src:e.attr('src'),tagName:e.tagName()};});}
  };
  global.console={log:bridge.log,error:bridge.log,warn:bridge.log,info:bridge.log};

  function decodeEntities(value){
    var named={amp:'&',lt:'<',gt:'>',quot:'"',apos:"'",nbsp:' '};
    return String(value||'').replace(/&(#x[0-9a-f]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);/gi,function(all,key){
      var lower=key.toLowerCase();
      if(lower.charAt(0)==='#'){
        var hex=lower.charAt(1)==='x';var n=parseInt(lower.substring(hex?2:1),hex?16:10);
        return isNaN(n)?all:String.fromCharCode(n);
      }
      return named[lower]||all;
    });
  }
  function escapeText(value){return String(value).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
  function escapeAttr(value){return escapeText(value).replace(/"/g,'&quot;');}
  function TextNode(text,parent,raw){this.value=text;this._parent=parent||null;this.raw=!!raw;}
  TextNode.prototype.outerHtml=function(){return this.raw?this.value:escapeText(this.value);};

  function Element(tag,attrs,parent,baseUri,document){
    this._tag=String(tag||'').toLowerCase();this._attrs=attrs||{};this._parent=parent||null;
    this._children=[];this._baseUri=baseUri||'';this._document=document||this;
  }
  function Document(baseUri){Element.call(this,'#document',{},null,baseUri||'',this);}
  Document.prototype=Object.create(Element.prototype);Document.prototype.constructor=Document;
  var voidTags={area:1,base:1,br:1,col:1,embed:1,hr:1,img:1,input:1,link:1,meta:1,param:1,source:1,track:1,wbr:1,'amp-img':1};

  Element.prototype.tagName=function(){return this._tag==='#document'?'#root':this._tag;};
  Element.prototype.attr=function(name){name=String(name).toLowerCase();return Object.prototype.hasOwnProperty.call(this._attrs,name)?String(this._attrs[name]):'';};
  Element.prototype.hasAttr=function(name){return Object.prototype.hasOwnProperty.call(this._attrs,String(name).toLowerCase());};
  Element.prototype.id=function(){return this.attr('id');};
  Element.prototype.className=function(){return this.attr('class');};
  Element.prototype.children=function(){return new Elements(this._children.filter(function(n){return n instanceof Element;}));};
  Element.prototype.parent=function(){return this._parent instanceof Element?this._parent:null;};
  Element.prototype.nextElementSibling=function(){var p=this.parent();if(!p)return null;var a=p.children()._arr,i=a.indexOf(this);return i>=0&&i+1<a.length?a[i+1]:null;};
  Element.prototype.previousElementSibling=function(){var p=this.parent();if(!p)return null;var a=p.children()._arr,i=a.indexOf(this);return i>0?a[i-1]:null;};
  Element.prototype.remove=function(){if(!this._parent)return;var i=this._parent._children.indexOf(this);if(i>=0)this._parent._children.splice(i,1);this._parent=null;};
  Element.prototype.release=function(){};
  Element.prototype.ownText=function(){return normalizeText(this._children.filter(function(n){return n instanceof TextNode;}).map(function(n){return n.value;}).join(' '));};
  Element.prototype.text=function(){var out=[];(function visit(node){node._children.forEach(function(child){if(child instanceof TextNode)out.push(child.value);else visit(child);});})(this);return normalizeText(out.join(' '));};
  Element.prototype.html=function(){return this._children.map(function(n){return n.outerHtml();}).join('');};
  Element.prototype.outerHtml=function(){
    if(this._tag==='#document')return this.html();
    var attrs='';for(var key in this._attrs)if(Object.prototype.hasOwnProperty.call(this._attrs,key))attrs+=' '+key+'="'+escapeAttr(this._attrs[key])+'"';
    return '<'+this._tag+attrs+'>'+(voidTags[this._tag]?'':this.html()+'</'+this._tag+'>');
  };
  Element.prototype.absUrl=function(name){return resolveUrl(this._baseUri,this.attr(name));};
  Element.prototype.select=function(selector){return querySelectorAll(this,String(selector));};
  Element.prototype.selectFirst=function(selector){var values=this.select(selector);return values.length?values[0]:null;};
  Element.prototype.getElementsByTag=function(tag){return this.select(String(tag));};
  Element.prototype.getElementsByClass=function(name){return this.select('.'+String(name));};
  Element.prototype.getElementById=function(id){return this.selectFirst('#'+String(id));};
  Element.prototype.toString=function(){return this.outerHtml();};

  function Elements(values){this._arr=values||[];this.length=this._arr.length;for(var i=0;i<this.length;i++)this[i]=this._arr[i];}
  Elements.prototype.get=function(i){return i>=0&&i<this.length?this._arr[i]:null;};
  Elements.prototype.first=function(){return this.get(0);};Elements.prototype.last=function(){return this.get(this.length-1);};
  Elements.prototype.size=function(){return this.length;};Elements.prototype.isEmpty=function(){return this.length===0;};
  Elements.prototype.forEach=function(fn){for(var i=0;i<this.length;i++)fn(this._arr[i],i);};
  Elements.prototype.map=function(fn){var out=[];for(var i=0;i<this.length;i++)out.push(fn(this._arr[i],i));return out;};
  Elements.prototype.filter=function(fn){var out=[];for(var i=0;i<this.length;i++)if(fn(this._arr[i],i))out.push(this._arr[i]);return new Elements(out);};
  Elements.prototype.text=function(){return normalizeText(this.map(function(e){return e.text();}).join(' '));};
  Elements.prototype.attr=function(name){return this.length?this._arr[0].attr(name):'';};
  Elements.prototype.hasAttr=function(name){return this.length?this._arr[0].hasAttr(name):false;};
  Elements.prototype.html=function(){return this.length?this._arr[0].html():'';};
  Elements.prototype.select=function(selector){var out=[];this.forEach(function(e){e.select(selector).forEach(function(x){if(out.indexOf(x)<0)out.push(x);});});return new Elements(out);};
  Elements.prototype.eachAttr=function(name){return this.map(function(e){return e.attr(name);});};Elements.prototype.eachText=function(){return this.map(function(e){return e.text();});};
  Elements.prototype.releaseAll=function(){};

  function normalizeText(value){return String(value||'').replace(/\s+/g,' ').trim();}
  function resolveUrl(base,value){
    value=String(value||'');base=String(base||'');if(!value)return '';
    if(/^[a-z][a-z0-9+.-]*:/i.test(value))return value;
    var scheme=(base.match(/^([a-z][a-z0-9+.-]*):/i)||[])[1]||'https';
    if(value.substring(0,2)==='//')return scheme+':'+value;
    var origin=(base.match(/^([a-z][a-z0-9+.-]*:\/\/[^\/]+)/i)||[])[1]||'';
    if(value.charAt(0)==='/')return origin+value;
    var clean=base.split('#')[0].split('?')[0],slash=clean.lastIndexOf('/');
    return (slash>=scheme.length+3?clean.substring(0,slash+1):clean.replace(/\/?/,'/'))+value;
  }
  function parseAttributes(raw){
    var attrs={},re=/([^\s=\/]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g,m;
    while((m=re.exec(raw)))attrs[String(m[1]).toLowerCase()]=decodeEntities(m[2]!==undefined?m[2]:(m[3]!==undefined?m[3]:(m[4]!==undefined?m[4]:'')));
    return attrs;
  }
  function parseHtml(html,baseUri){
    html=String(html||'');var doc=new Document(baseUri||''),stack=[doc],i=0;
    while(i<html.length){
      var parent=stack[stack.length-1];
      if(parent._tag==='script'||parent._tag==='style'){
        var closeToken='</'+parent._tag,closeAt=html.toLowerCase().indexOf(closeToken,i);
        if(closeAt<0){parent._children.push(new TextNode(html.substring(i),parent,true));stack.pop();break;}
        parent._children.push(new TextNode(html.substring(i,closeAt),parent,true));
        var closeEnd=findTagEnd(html,closeAt+1);
        stack.pop();i=closeEnd<0?html.length:closeEnd+1;continue;
      }
      var open=html.indexOf('<',i);
      if(open<0){parent._children.push(new TextNode(decodeEntities(html.substring(i)),parent,false));break;}
      if(open>i)parent._children.push(new TextNode(decodeEntities(html.substring(i,open)),parent,false));
      if(html.substring(open,open+4)==='<!--'){var endComment=html.indexOf('-->',open+4);i=endComment<0?html.length:endComment+3;continue;}
      var end=findTagEnd(html,open+1);if(end<0){parent._children.push(new TextNode(decodeEntities(html.substring(open)),parent,false));break;}
      var raw=html.substring(open+1,end).trim();i=end+1;if(!raw||raw.charAt(0)==='!'||raw.charAt(0)==='?')continue;
      if(raw.charAt(0)==='/'){
        var closing=raw.substring(1).trim().split(/\s+/)[0].toLowerCase();
        for(var s=stack.length-1;s>0;s--)if(stack[s]._tag===closing){stack.length=s;break;}
        continue;
      }
      var selfClosing=/\/\s*$/.test(raw);if(selfClosing)raw=raw.replace(/\/\s*$/,'');
      var match=raw.match(/^([^\s]+)/);if(!match)continue;var tag=match[1].toLowerCase();
      var element=new Element(tag,parseAttributes(raw.substring(match[0].length)),parent,baseUri||'',doc);
      parent._children.push(element);if(!selfClosing&&!voidTags[tag])stack.push(element);
    }
    return doc;
  }
  function findTagEnd(html,start){var quote='';for(var i=start;i<html.length;i++){var c=html.charAt(i);if(quote){if(c===quote)quote='';}else if(c==='"'||c==="'")quote=c;else if(c==='>')return i;}return -1;}

  function splitTopLevel(value,separator){
    var out=[],start=0,round=0,square=0,quote='';
    for(var i=0;i<value.length;i++){var c=value.charAt(i);if(quote){if(c===quote&&value.charAt(i-1)!=='\\')quote='';continue;}if(c==='"'||c==="'"){quote=c;continue;}if(c==='(')round++;else if(c===')')round--;else if(c==='[')square++;else if(c===']')square--;else if(c===separator&&round===0&&square===0){out.push(value.substring(start,i));start=i+1;}}
    out.push(value.substring(start));return out;
  }
  function selectorSteps(selector){
    selector=selector.trim();var out=[],buf='',pending='desc',round=0,square=0,quote='';
    function push(){var value=buf.trim();if(value){out.push({combinator:pending,simple:value});buf='';pending='desc';}}
    for(var i=0;i<selector.length;i++){var c=selector.charAt(i);if(quote){buf+=c;if(c===quote&&selector.charAt(i-1)!=='\\')quote='';continue;}if(c==='"'||c==="'"){quote=c;buf+=c;continue;}if(c==='('){round++;buf+=c;continue;}if(c===')'){round--;buf+=c;continue;}if(c==='['){square++;buf+=c;continue;}if(c===']'){square--;buf+=c;continue;}
      if(round===0&&square===0&&(c==='>'||c==='+')){push();pending=c==='>'?'child':'adjacent';continue;}
      if(round===0&&square===0&&/\s/.test(c)){if(buf.trim())push();continue;}buf+=c;
    }push();return out;
  }
  function descendants(element){var out=[];(function walk(node){node._children.forEach(function(child){if(child instanceof Element){out.push(child);walk(child);}});})(element);return out;}
  function candidates(element,combinator){if(combinator==='child')return element.children()._arr;if(combinator==='adjacent'){var n=element.nextElementSibling();return n?[n]:[];}return descendants(element);}
  function querySelectorAll(root,selector){
    var result=[];splitTopLevel(String(selector),',').forEach(function(group){var steps=selectorSteps(group),current=[root];steps.forEach(function(step){var next=[];current.forEach(function(node){candidates(node,step.combinator).forEach(function(candidate){if(matchesSimple(candidate,step.simple)&&next.indexOf(candidate)<0)next.push(candidate);});});current=next;});current.forEach(function(e){if(result.indexOf(e)<0)result.push(e);});});return new Elements(result);
  }
  function readBalanced(value,start,open,close){var depth=0,quote='';for(var i=start;i<value.length;i++){var c=value.charAt(i);if(quote){if(c===quote&&value.charAt(i-1)!=='\\')quote='';continue;}if(c==='"'||c==="'"){quote=c;continue;}if(c===open)depth++;else if(c===close){depth--;if(depth===0)return i;}}return value.length-1;}
  function stripQuotes(value){value=String(value||'').trim();if((value.charAt(0)==='"'&&value.charAt(value.length-1)==='"')||(value.charAt(0)==="'"&&value.charAt(value.length-1)==="'"))return value.substring(1,value.length-1);return value;}
  function matchesAttribute(element,body){
    var m=body.match(/^\s*([^\s~|^*!=\x24]+)\s*(?:(\^=|\*=|\x24=|~=|\|=|=)\s*(?:"([^"]*)"|'([^']*)'|([^\s]+)))?\s*$/);if(!m)return false;
    var name=m[1].toLowerCase(),op=m[2],expected=m[3]!==undefined?m[3]:(m[4]!==undefined?m[4]:(m[5]!==undefined?m[5]:''));
    if(!element.hasAttr(name))return false;if(!op)return true;var actual=element.attr(name);
    if(op==='=')return actual===expected;if(op==='^=')return actual.indexOf(expected)===0;if(op==='*=')return actual.indexOf(expected)>=0;if(op.charCodeAt(0)===36)return actual.lastIndexOf(expected)===actual.length-expected.length;if(op==='~=')return actual.split(/\s+/).indexOf(expected)>=0;if(op==='|=')return actual===expected||actual.indexOf(expected+'-')===0;return false;
  }
  function rootOf(element){var current=element;while(current._parent)current=current._parent;return current;}
  function matchesComplex(element,selector){var selected=querySelectorAll(rootOf(element),selector)._arr;return selected.indexOf(element)>=0;}
  function matchesSimple(element,simple){
    var i=0,tag=simple.substring(i).match(/^[a-zA-Z*][a-zA-Z0-9_-]*/);if(tag){if(tag[0]!=='*'&&element._tag!==tag[0].toLowerCase())return false;i+=tag[0].length;}
    while(i<simple.length){var c=simple.charAt(i);
      if(c==='#'||c==='.'){var m=simple.substring(i+1).match(/^[a-zA-Z0-9_-]+/);if(!m)return false;var value=m[0];if(c==='#'&&element.id()!==value)return false;if(c==='.'&&element.className().split(/\s+/).indexOf(value)<0)return false;i+=value.length+1;continue;}
      if(c==='['){var end=readBalanced(simple,i,'[',']');if(!matchesAttribute(element,simple.substring(i+1,end)))return false;i=end+1;continue;}
      if(c===':'){var nm=simple.substring(i+1).match(/^[a-zA-Z-]+/);if(!nm)return false;var name=nm[0].toLowerCase();i+=name.length+1;var arg=null;if(simple.charAt(i)==='('){var endp=readBalanced(simple,i,'(',')');arg=simple.substring(i+1,endp);i=endp+1;}
        var siblings=element.parent()?element.parent().children()._arr:[element],position=siblings.indexOf(element)+1;
        if(name==='contains'&&element.text().indexOf(stripQuotes(arg))<0)return false;
        else if(name==='has'&&element.select(arg||'').isEmpty())return false;
        else if(name==='not'&&matchesComplex(element,arg||''))return false;
        else if(name==='first-child'&&position!==1)return false;
        else if(name==='last-child'&&position!==siblings.length)return false;
        else if(name==='nth-child'){var wanted=stripQuotes(arg);if(wanted==='odd'&&position%2!==1)return false;else if(wanted==='even'&&position%2!==0)return false;else if(wanted!=='odd'&&wanted!=='even'&&position!==parseInt(wanted,10))return false;}
        continue;
      }
      i++;
    }
    return true;
  }

  global.Jsoup={parse:function(html,baseUri){return parseHtml(String(html),baseUri?String(baseUri):'');}};
  global.fetchAndParse=function(url,baseUri){var html=bridge.httpGet(String(url));return !html||html.error?null:Jsoup.parse(html,baseUri||url);};
  global.SManga={create:function(){return{url:'',title:'',author:null,artist:null,description:null,genre:null,status:0,thumbnailUrl:null,initialized:false};},UNKNOWN:0,ONGOING:1,COMPLETED:2,LICENSED:3,PUBLISHING_FINISHED:4,CANCELLED:5,ON_HIATUS:6};
  global.SChapter={create:function(){return{url:'',name:'',scanlator:null,dateUpload:0,chapterNumber:-1};}};
  global.Page=function(index,url,imageUrl){this.index=index||0;this.url=url||'';this.imageUrl=imageUrl||null;};
  global.MangasPage=function(mangas,hasNextPage){this.mangas=mangas||[];this.hasNextPage=!!hasNextPage;};
  if(!String.prototype.substringAfter)String.prototype.substringAfter=function(d){var i=this.indexOf(d);return i>=0?this.substring(i+d.length):String(this);};
  if(!String.prototype.substringBefore)String.prototype.substringBefore=function(d){var i=this.indexOf(d);return i>=0?this.substring(0,i):String(this);};
  if(!String.prototype.substringAfterLast)String.prototype.substringAfterLast=function(d){var i=this.lastIndexOf(d);return i>=0?this.substring(i+d.length):String(this);};
  if(!String.prototype.substringBeforeLast)String.prototype.substringBeforeLast=function(d){var i=this.lastIndexOf(d);return i>=0?this.substring(0,i):String(this);};
  global.setUrlWithoutDomain=function(object,url){var match=String(url).match(/^https?:\/\/[^\/]+(\/[^#]*)/i);object.url=match?match[1]:String(url);};
  global.__shinsouSelectSource=function(requestedId){
    if(requestedId!==null&&requestedId!==undefined&&typeof global.sources==='object'&&global.sources){
      var requested=String(requestedId),matches=[];
      Object.keys(global.sources).forEach(function(key){
        var candidate=global.sources[key];
        if(!candidate||typeof candidate!=='object')return;
        var candidateId=candidate.id!==undefined?candidate.id:(candidate.sourceId!==undefined?candidate.sourceId:key);
        if(String(candidateId)===requested)matches.push(candidate);
      });
      if(matches.length!==1)throw new Error('Plugin does not export exactly one source '+requested);
      global.source=matches[0];
    }
    if(typeof global.source!=='object'||!global.source)throw new Error('Plugin does not export source');
    if(requestedId!==null&&requestedId!==undefined){
      global.source.id=String(requestedId);
      if(global.__shinsouRequestedSourceName!==undefined)global.source.name=global.__shinsouRequestedSourceName;
      if(global.__shinsouRequestedSourceLang!==undefined)global.source.lang=global.__shinsouRequestedSourceLang;
      if(global.__shinsouRequestedSourceBaseUrl!==undefined)global.source.baseUrl=global.__shinsouRequestedSourceBaseUrl;
    }
    return true;
  };
  global.__shinsouMetadata=function(){if(typeof source!=='object'||!source)throw new Error('Plugin does not export source');return JSON.stringify({baseUrl:source.baseUrl||'',supportsLatest:!!source.supportsLatest,supportsLogin:!!source.supportsLogin,headers:source.headers||{}});};
  global.__shinsouPreferences=function(){if(typeof source!=='object'||!source)return '[]';var values=typeof source.getPreferenceDefinitions==='function'?source.getPreferenceDefinitions():(source.preferences||[]);return JSON.stringify(values||[]);};
  global.__shinsouInvoke=function(method,argsJson){bridge.domReleaseAll();if(typeof source!=='object'||!source||typeof source[method]!=='function')throw new Error('Plugin has no function '+method);var result=source[method].apply(source,JSON.parse(argsJson||'[]'));return JSON.stringify(result===undefined?null:result);};
})(this);
"""
