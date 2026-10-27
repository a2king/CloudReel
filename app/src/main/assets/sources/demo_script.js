var source = {
  meta: { name: "内置脚本样例", version: "1.0.0" },
  home: function (ctx) {
    return {
      items: [
        { id: "bunny", title: "大雄兔", year: "2008", remarks: "脚本来源" },
        { id: "script-demo", title: "脚本样片", year: "2010", remarks: "脚本" }
      ]
    };
  },
  search: function (ctx, keyword) {
    var items = [
      { id: "bunny", title: "大雄兔", year: "2008" },
      { id: "script-demo", title: "脚本样片", year: "2010" }
    ];
    var matched = [];
    for (var i = 0; i < items.length; i++) {
      if (!keyword || items[i].title.indexOf(keyword) >= 0) matched.push(items[i]);
    }
    return { items: matched };
  },
  detail: function (ctx, id) {
    if (id === "bunny") {
      return {
        id: id,
        title: "大雄兔",
        year: "2008",
        desc: "脚本返回的公开测试片，用来和内置片库合并。",
        lines: [{
          name: "脚本线路",
          episodes: [{ name: "正片", url: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4" }]
        }]
      };
    }
    return {
      id: id,
      title: "脚本样片",
      year: "2010",
      desc: "脚本返回的另一条公开测试片。",
      lines: [{
        name: "脚本线路",
        episodes: [{ name: "正片", url: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4" }]
      }]
    };
  }
};
