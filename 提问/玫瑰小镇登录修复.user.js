// ==UserScript==
// @name         玫瑰小镇登录修复
// @namespace    https://meigui.qq.com/
// @version      3.2
// @description  修复Chrome中QQ登录组件不显示的问题 + 登录后自动显示Cookie
// @author       nova
// @match        https://meigui.qq.com/*
// @match        https://1314.qq.com/*
// @grant        GM_xmlhttpRequest
// @grant        GM_setClipboard
// @connect      cgi.meigui.qq.com
// @run-at       document-start
// ==/UserScript==

(function() {
    'use strict';

    // ====== 问题根因 ======
    // Chrome 106+ 废弃 document.domain，导致 cgi.meigui.qq.com 的 iframe 代理
    // 无法访问 parent.document，跨域AJAX静默失败，登录组件永远不弹出。
    //
    // 修复策略：
    // 1. 替换 AjaxTool.sendRequest（闭包内有自己的局部 jqAjax）
    // 2. 替换 window.jqAjax（全局版本）
    // 3. 保险：如果时序竞争导致首次 checkGrayStrategy 未被拦截，自动重试

    // ====== 油猴侧：监听页面请求事件，用 GM_xmlhttpRequest 发送 ======
    window.addEventListener('__mgfix_req__', function(e) {
        var d = e.detail;
        GM_xmlhttpRequest({
            method: d.method || 'GET',
            url: d.url,
            data: d.data || undefined,
            headers: d.data ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {},
            anonymous: false,
            onload: function(resp) {
                window.dispatchEvent(new CustomEvent('__mgfix_resp__', {
                    detail: { id: d.id, ok: true, text: resp.responseText }
                }));
            },
            onerror: function() {
                window.dispatchEvent(new CustomEvent('__mgfix_resp__', {
                    detail: { id: d.id, ok: false }
                }));
            }
        });
    });

    // ====== 页面侧注入 ======
    // 在 DOMContentLoaded 时注入（早于 jQuery.ready，因为我们注册得更早）
    document.addEventListener('DOMContentLoaded', function() {
        var s = document.createElement('script');
        s.textContent = '(' + function() {
            // --- 响应监听 ---
            var _cbs = {};
            var _n = 0;
            window.addEventListener('__mgfix_resp__', function(e) {
                var d = e.detail;
                var cb = _cbs[d.id];
                if (!cb) return;
                delete _cbs[d.id];
                if (d.ok && cb.success) {
                    var parsed;
                    try { parsed = JSON.parse(d.text); } catch(x) { parsed = d.text; }
                    try { cb.success(parsed); } catch(x) { console.error('[MGFix]', x); }
                } else if (!d.ok && cb.error) {
                    try { cb.error(); } catch(x) { console.error('[MGFix]', x); }
                }
            });

            // --- 发送请求 ---
            function gmRequest(url, method, data, successFn, errorFn) {
                var id = '_mgf_' + (++_n) + '_' + Date.now();
                _cbs[id] = { success: successFn, error: errorFn };
                var dataStr = null;
                if (data) {
                    if (typeof data === 'string') {
                        dataStr = data;
                    } else {
                        var parts = [];
                        for (var k in data) {
                            if (data.hasOwnProperty(k)) {
                                parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(data[k]));
                            }
                        }
                        dataStr = parts.join('&');
                    }
                }
                window.dispatchEvent(new CustomEvent('__mgfix_req__', {
                    detail: { id: id, url: url, method: method, data: dataStr }
                }));
            }

            // --- 替换 AjaxTool.sendRequest ---
            function patchAjaxTool() {
                if (typeof AjaxTool === 'undefined') return false;
                var origPrefixCgi = 'https://cgi.meigui.qq.com/cgi-bin/';
                AjaxTool.sendRequest = function(cgi, onSuccess, onError, param, newPrefixCgi, newProxyUrl, isPost) {
                    var prefix = newPrefixCgi || origPrefixCgi;
                    var method = (isPost !== false && isPost !== 0) ? 'POST' : 'GET';
                    gmRequest(prefix + cgi, method, param || null,
                        function(data) { onSuccess && onSuccess(data); },
                        function() { onError && onError(); }
                    );
                };
                return true;
            }

            // --- 替换全局 jqAjax ---
            function patchJqAjax() {
                window.jqAjax = function(req_data, proxy) {
                    gmRequest(req_data.url, req_data.type || 'GET', req_data.data || null,
                        req_data.success, req_data.error);
                };
            }

            // 执行替换
            patchAjaxTool();
            patchJqAjax();

            // --- 保险机制 ---
            // 如果因时序问题 checkGrayStrategy 已经执行过了（登录框没弹出），
            // 检测并重新触发
            setTimeout(function() {
                if (typeof Module !== 'undefined' && Module.Login) {
                    var uin = Module.Login.getUin();
                    var openid = Module.Login.getOpenid();
                    // 如果没有有效登录态，且登录框没显示，重新触发
                    if (uin <= 10000 && (!openid || openid.length <= 0)) {
                        var loginDiv = document.getElementById('ModuleLoginDiv');
                        if (!loginDiv || loginDiv.style.display === 'none' || loginDiv.style.visibility === 'hidden') {
                            console.log('[玫瑰小镇修复] 检测到登录未触发，重新执行 checkGrayStrategy');
                            if (typeof checkGrayStrategy === 'function') {
                                checkGrayStrategy();
                            }
                        }
                    }
                }
            }, 500);

            console.log('[玫瑰小镇修复] v3.2 加载完成');
        } + ')();';
        document.documentElement.appendChild(s);
        s.remove();
    }, false);

    // ====== Cookie 获取功能 ======
    // 页面右上角显示一个按钮，点击后显示全部 Cookie 并复制到剪贴板
    document.addEventListener('DOMContentLoaded', function() {
        // 悬浮按钮
        var btn = document.createElement('div');
        btn.textContent = '获取Cookie';
        btn.style.cssText = 'position:fixed;top:10px;right:10px;z-index:99999;background:#12b7f5;' +
            'color:#fff;padding:8px 16px;border-radius:6px;cursor:pointer;font-size:14px;' +
            'font-family:Microsoft YaHei,sans-serif;box-shadow:0 2px 8px rgba(0,0,0,0.2);';
        document.body.appendChild(btn);

        btn.onclick = function() {
            var cookie = document.cookie;
            // 复制到剪贴板
            if (typeof GM_setClipboard === 'function') {
                GM_setClipboard(cookie, 'text');
            } else {
                navigator.clipboard.writeText(cookie).catch(function(){});
            }
            // 显示面板
            var existing = document.getElementById('__mgfix_cookie_panel__');
            if (existing) existing.remove();

            var panel = document.createElement('div');
            panel.id = '__mgfix_cookie_panel__';
            panel.style.cssText = 'position:fixed;top:50px;right:10px;z-index:99999;background:#fff;' +
                'border:2px solid #12b7f5;border-radius:8px;padding:12px;width:380px;' +
                'box-shadow:0 4px 12px rgba(0,0,0,0.15);font-family:Consolas,monospace;font-size:12px;';
            panel.innerHTML = '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">' +
                '<b style="color:#12b7f5;font-size:13px;">Cookie (已复制到剪贴板)</b>' +
                '<span id="__mgfix_close__" style="cursor:pointer;font-size:18px;color:#999;">✕</span></div>' +
                '<textarea id="__mgfix_cookie_text__" readonly style="width:100%;height:120px;' +
                'border:1px solid #ddd;border-radius:4px;padding:6px;font-size:11px;' +
                'resize:vertical;word-break:break-all;"></textarea>';
            document.body.appendChild(panel);
            document.getElementById('__mgfix_cookie_text__').value = cookie;
            document.getElementById('__mgfix_close__').onclick = function() { panel.remove(); };
        };
    });
})();
