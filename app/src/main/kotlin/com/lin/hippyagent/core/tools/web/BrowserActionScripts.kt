package com.lin.hippyagent.core.tools.web

/**
 * 浏览器交互 JS 脚本集合
 *
 * 参考: OpenClaw agent-browser/scripts.ts + actions.ts
 * 所有 JS 脚本使用顶层 private val 单例，避免函数内重复构造 Regex
 */
object BrowserActionScripts {

    /** 点击包含特定文本的元素 */
    val CLICK_BY_TEXT: String get() = """
    (function(text) {
        var elements = document.querySelectorAll('a, button, span, div, [role=button]');
        for (var el of elements) {
            if (el.innerText && el.innerText.trim() === text) {
                el.click();
                return JSON.stringify({ success: true, text: el.innerText.trim().slice(0, 100) });
            }
        }
        return JSON.stringify({ success: false, error: 'No element with text: ' + text });
    })('%s')
    """.trimIndent()

    /** 按 CSS 选择器点击 */
    val CLICK_BY_SELECTOR: String get() = """
    (function(selector) {
        var el = document.querySelector(selector);
        if (el) { el.click(); return JSON.stringify({ success: true }); }
        return JSON.stringify({ success: false, error: 'No element for selector: ' + selector });
    })('%s')
    """.trimIndent()

    /** 按索引点击（从 get_interactable 获取的索引） */
    val CLICK_BY_INDEX: String get() = """
    (function(index) {
        var interactable = 'a, button, input, select, textarea, [role=button], [tabindex]';
        var elements = document.querySelectorAll(interactable);
        if (index >= 0 && index < elements.length) {
            elements[index].click();
            return JSON.stringify({ success: true });
        }
        return JSON.stringify({ success: false, error: 'Invalid index: ' + index });
    })(%d)
    """.trimIndent()

    /** 输入文本 */
    val TYPE_TEXT: String get() = """
    (function(selector, text) {
        var el = document.querySelector(selector);
        if (!el) return JSON.stringify({ success: false, error: 'No element for: ' + selector });
        el.focus();
        el.value = text;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return JSON.stringify({ success: true });
    })('%s', '%s')
    """.trimIndent()

    /** 获取可见文本（排除 script/style/noscript） */
    val GET_VISIBLE_TEXT: String get() = """
    (function() {
        var clone = document.body.cloneNode(true);
        clone.querySelectorAll('script, style, noscript, svg').forEach(function(s) { s.remove(); });
        return clone.innerText || '';
    })()
    """.trimIndent()

    /** 获取页面 HTML */
    val GET_HTML: String get() = """
    (function() {
        return document.documentElement.outerHTML || '';
    })()
    """.trimIndent()

    /** 滚动页面 */
    val SCROLL: String get() = """
    (function(direction, amount) {
        var opts = { top: 0, left: 0, behavior: 'smooth' };
        if (direction === 'down') opts.top = amount || 500;
        else if (direction === 'up') opts.top = -(amount || 500);
        window.scrollBy(opts);
        return JSON.stringify({ success: true, scrollY: window.scrollY });
    })('%s', %d)
    """.trimIndent()

    /** 等待元素出现（轮询） */
    val WAIT_ELEMENT: String get() = """
    (function(selector, timeoutMs) {
        return new Promise(function(resolve) {
            var start = Date.now();
            var check = function() {
                var el = document.querySelector(selector);
                if (el) { resolve(JSON.stringify({ found: true })); return; }
                if (Date.now() - start > timeoutMs) { resolve(JSON.stringify({ found: false })); return; }
                setTimeout(check, 100);
            };
            check();
        });
    })('%s', %d)
    """.trimIndent()

    /** 获取所有可交互元素的列表 */
    val GET_INTERACTABLE_ELEMENTS: String get() = """
    (function() {
        var interactable = 'a, button, input, select, textarea, [role=button], [tabindex]';
        var elements = document.querySelectorAll(interactable);
        return JSON.stringify(Array.from(elements).slice(0, 100).map(function(el, i) {
            return {
                index: i,
                tag: el.tagName.toLowerCase(),
                text: (el.innerText || el.value || el.placeholder || '').trim().slice(0, 80),
                id: el.id || '',
                className: (el.className || '').slice(0, 40),
                rect: el.getBoundingClientRect() ? {
                    x: Math.round(el.getBoundingClientRect().x),
                    y: Math.round(el.getBoundingClientRect().y),
                    w: Math.round(el.getBoundingClientRect().width),
                    h: Math.round(el.getBoundingClientRect().height)
                } : null
            };
        }));
    })()
    """.trimIndent()

    /** 获取结构化内容（标题、描述、标题列表、链接） */
    val GET_STRUCTURED_CONTENT: String get() = """
    (function() {
        var title = document.title || '';
        var meta = document.querySelector('meta[name=description]');
        var desc = meta ? meta.content : '';
        var headings = Array.from(document.querySelectorAll('h1,h2,h3')).map(function(h) {
            return h.tagName + ': ' + h.innerText.trim().slice(0, 100);
        });
        var links = Array.from(document.querySelectorAll('a[href]'))
            .filter(function(a) { return a.href && a.href.startsWith('http'); })
            .slice(0, 20)
            .map(function(a) { return (a.innerText || a.href).trim().slice(0, 80) + ' -> ' + a.href; });
        return JSON.stringify({ title: title, description: desc, headings: headings, links: links });
    })()
    """.trimIndent()

    /** 获取页面标题 */
    val GET_TITLE: String get() = """
    (function() { return document.title || ''; })()
    """.trimIndent()
}

/** 页面交互结果 — 对应 JS 返回的 JSON */
data class ActionResult(
    val success: Boolean,
    val error: String? = null,
    val text: String? = null
)

/** 可交互元素 */
data class InteractableElement(
    val index: Int,
    val tag: String,
    val text: String,
    val id: String,
    val className: String,
    val rect: ElementRect? = null
)

data class ElementRect(
    val x: Float, val y: Float,
    val w: Float, val h: Float
)

/** 结构化内容 */
data class StructuredContent(
    val title: String,
    val description: String,
    val headings: List<String> = emptyList(),
    val links: List<String> = emptyList()
)
