package com.yingshicang.tv.core.lan

fun lanPage(token: String): String {
    val safeToken = token.filter { it.isLetterOrDigit() }
    return """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>映视提交</title>
        <style>
        body{margin:0;font-family:sans-serif;background:#f4f6f8;color:#17202a}
        main{max-width:680px;margin:0 auto;padding:20px}
        h1{font-size:24px;margin:0 0 8px}
        p{color:#52606d;line-height:1.5}
        section{background:#fff;border-radius:12px;padding:16px;margin:14px 0}
        label{display:block;margin:10px 0 4px;font-size:14px}
        input,textarea,select{width:100%;box-sizing:border-box;font-size:16px;padding:10px;border:1px solid #d9e2ec;border-radius:8px}
        textarea{min-height:140px}
        button{margin-top:12px;background:#245bdb;color:#fff;border:0;border-radius:8px;padding:12px 16px;font-size:16px}
        #msg{min-height:24px;font-weight:600}
        </style>
        </head>
        <body>
        <main>
        <h1>提交到电视</h1>
        <p>手机和电视要在同一个 Wi-Fi。提交时保持映视打开。这个地址带有临时口令，用完请在电视上关闭。</p>
        <p id="msg"></p>
        <section>
        <h2>影视数据源</h2>
        <label>名称</label><input id="cmsName" placeholder="我的片库">
        <label>CMS 接口或 TVBox 多源配置地址</label><input id="cmsUrl" placeholder="https://example.com/api.php/provide/vod/ 或 tvbox.json">
        <button onclick="send({type:'cms',name:val('cmsName'),url:val('cmsUrl')})">提交接口</button>
        </section>
        <section>
        <h2>直播</h2>
        <label>名称</label><input id="liveName" placeholder="我的直播">
        <label>M3U、JSON 或 XMLTV 地址</label><input id="liveUrl" placeholder="http://">
        <label>或直接粘贴内容</label><textarea id="liveText" placeholder="#EXTM3U"></textarea>
        <label>节目单地址或 XMLTV 正文，可留空</label><textarea id="liveEpg"></textarea>
        <button onclick="sendLive()">提交直播</button>
        </section>
        <section>
        <h2>网盘</h2>
        <label>类型</label>
        <select id="driveType"><option value="webdav">WebDAV</option><option value="alist">Alist</option></select>
        <label>名称</label><input id="driveName" placeholder="家里的 Alist">
        <label>地址</label><input id="driveUrl" placeholder="http://192.168.1.10:5244">
        <label>用户名，Alist 可留空</label><input id="driveUser">
        <label>密码或 Token</label><input id="driveSecret" type="password">
        <button onclick="send({type:'drive',provider:val('driveType'),name:val('driveName'),endpoint:val('driveUrl'),username:val('driveUser'),secret:val('driveSecret')})">提交网盘</button>
        </section>
        <section>
        <h2>脚本源</h2>
        <p>脚本在电视里运行，只能通过 ctx.httpGet 访问 http 或 https，不能读写电视上的文件。使用同步函数，并赋值给 source。</p>
        <label>名称</label><input id="scriptName" placeholder="我的脚本">
        <label>脚本地址</label><input id="scriptUrl" placeholder="https://example.com/source.js">
        <label>或直接粘贴脚本</label><textarea id="scriptCode"></textarea>
        <button onclick="document.getElementById('scriptCode').value=EXAMPLE">填入示例</button>
        <button onclick="send({type:'script',name:val('scriptName'),url:val('scriptUrl'),code:val('scriptCode')})">提交脚本</button>
        </section>
        </main>
        <script>
        var TOKEN=${jsonString(safeToken)};
        var EXAMPLE=${jsonString(EXAMPLE_SCRIPT)};
        function val(id){return document.getElementById(id).value.trim();}
        function sendLive(){
          var epg=val('liveEpg');
          var payload={type:'live',name:val('liveName'),url:val('liveUrl'),text:val('liveText')};
          if(epg.indexOf('<')===0){payload.epg=epg;}else if(epg){payload.epgUrl=epg;}
          send(payload);
        }
        function send(payload){
          var box=document.getElementById('msg');
          box.style.color='#245bdb';
          box.textContent='正在提交…';
          fetch('/upload?token='+encodeURIComponent(TOKEN),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)})
            .then(function(r){return r.json();})
            .then(function(data){box.style.color=data.ok?'#137333':'#a33';box.textContent=data.message||'完成';})
            .catch(function(){box.style.color='#a33';box.textContent='提交失败，请确认电视上的映视还开着';});
        }
        </script>
        </body>
        </html>
    """.trimIndent()
}

private fun jsonString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("<", "\\u003c")
    return "\"$escaped\""
}

private val EXAMPLE_SCRIPT = """
var source = {
  meta: { name: "示例脚本" },
  home: function (ctx) {
    return { items: [{ id: "bunny", title: "脚本样片", year: "2008", remarks: "电影" }] };
  },
  search: function (ctx, keyword) {
    return { items: [{ id: "bunny", title: keyword || "脚本样片", year: "2008" }] };
  },
  detail: function (ctx, id) {
    return {
      id: id,
      title: "脚本样片",
      year: "2008",
      desc: "由脚本返回的公开测试片。",
      lines: [{ name: "默认", episodes: [{ name: "正片", url: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4" }] }]
    };
  }
};
""".trimIndent()
