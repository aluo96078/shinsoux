import JavaScriptCore
import XCTest

final class JSDomLibTests: XCTestCase {
    private func makeContext() -> (JSContext, errors: () -> [String]) {
        let context = JSContext()!
        var errors: [String] = []
        context.exceptionHandler = { _, exception in errors.append(exception?.toString() ?? "unknown") }
        return (context, { errors })
    }

    func testScriptEvaluatesAndDefinesTheDomClasses() {
        let (context, errors) = makeContext()
        context.evaluateScript(JSDomLib.script)
        XCTAssertEqual(errors(), [])
        for name in ["Jsoup", "Document", "Element"] {
            XCTAssertFalse(context.objectForKeyedSubscript(name).isUndefined, "\(name) is not defined")
        }
    }

    func testJsoupParseDelegatesToTheBridgeAndReturnsNullOnFailure() {
        let (context, errors) = makeContext()
        context.evaluateScript("""
        var bridge = {
            htmlParse: function(html) { return html === "bad" ? -1 : 7; },
            htmlParseFragment: function(html, base) { return 9; }
        };
        """)
        context.evaluateScript(JSDomLib.script)
        XCTAssertEqual(context.evaluateScript("Jsoup.parse('<p>x</p>')._hid")?.toInt32(), 7)
        XCTAssertEqual(context.evaluateScript("Jsoup.parse('<p>x</p>', 'https://a.b')._hid")?.toInt32(), 9)
        XCTAssertEqual(context.evaluateScript("Jsoup.parse('bad') === null")?.toBool(), true)
        XCTAssertEqual(errors(), [])
    }
}
