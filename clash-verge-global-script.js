// Clash Verge Rev 全局扩展脚本
// 用法：订阅页 -> 全局扩展脚本 -> 编辑文件 -> 粘贴本文件内容。
// 注意：只使用“全局扩展脚本”即可，不要再同时粘贴 Merge 覆写规则，避免互相覆盖。

const testUrl = "https://www.google.com/generate_204";

const domesticNameservers = [
  "https://223.5.5.5/dns-query",
  "https://doh.pub/dns-query",
  "https://dns.alidns.com/dns-query",
];

const foreignNameservers = [
  "https://1.1.1.1/dns-query",
  "https://194.242.2.2/dns-query",
  "https://public.dns.iij.jp/dns-query",
  "https://doh.opendns.com/dns-query",
];

const dnsConfig = {
  enable: true,
  listen: "0.0.0.0:1053",
  ipv6: false,
  "use-system-hosts": true,
  "prefer-h3": false,
  "respect-rules": true,
  "enhanced-mode": "fake-ip",
  "fake-ip-range": "198.18.0.1/16",
  "fake-ip-filter": [
    "+.lan",
    "+.local",
    "*.localdomain",
    "*.example",
    "*.invalid",
    "*.localhost",
    "*.test",
    "*.home.arpa",

    "dns.msftncsi.com",
    "+.msftconnecttest.com",
    "+.msftncsi.com",

    "localhost.ptlogin2.qq.com",
    "localhost.sec.qq.com",
    "localhost.work.weixin.qq.com",

    "+.qq.com",
    "+.weixin.qq.com",
    "+.wechat.com",
    "+.csdn.net",
    "+.csdnimg.cn",
    "+.zwu.edu.cn",

    "stun.+.+.+",
    "stun.+.+",
    "miwifi.com",
    "+.music.163.com",
    "*.126.net",
    "api-jooxtt.sanook.com",
    "streamoc.music.tc.qq.com",
    "mobileoc.music.tc.qq.com",
    "isure.stream.qqmusic.qq.com",
    "dl.stream.qqmusic.qq.com",
    "aqqmusic.tc.qq.com",
    "amobile.music.tc.qq.com",
    "+.xiaomi.com",
    "+.music.migu.cn",
    "music.migu.cn",
    "netis.cc",
    "+.ntp.org.cn",
    "+.openwrt.pool.ntp.org",
    "+.+.+.srv.nintendo.net",
    "+.+.stun.playstation.net",
    "speedtest.cros.wr.pvp.net",
    "+.xboxlive.com",
  ],
  "default-nameserver": ["223.5.5.5", "119.29.29.29", "1.1.1.1", "8.8.8.8"],
  nameserver: domesticNameservers,
  "proxy-server-nameserver": [
    "223.5.5.5",
    "119.29.29.29",
    "https://223.5.5.5/dns-query",
    "https://doh.pub/dns-query",
  ],
  "direct-nameserver": domesticNameservers,
  "direct-nameserver-follow-policy": true,
  "nameserver-policy": {
    "+.zwu.edu.cn": ["10.70.50.23", "10.70.50.25"],
    "geosite:private,cn,geolocation-cn": domesticNameservers,
  },
};

const ruleProviderMrs = {
  type: "http",
  format: "mrs",
  interval: 86400,
};

const ruleProviderYaml = {
  type: "http",
  format: "yaml",
  interval: 86400,
};

const metaGeosite = (name) =>
  `https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@meta/geo/geosite/${name}.mrs`;

const metaGeositeLite = (name) =>
  `https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@meta/geo-lite/geosite/${name}.mrs`;

const metaGeoip = (name) =>
  `https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@meta/geo/geoip/${name}.mrs`;

const metaGeoipLite = (name) =>
  `https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@meta/geo-lite/geoip/${name}.mrs`;

const loyal = (name) =>
  `https://cdn.jsdelivr.net/gh/Loyalsoldier/clash-rules@release/${name}.txt`;

const blackmatrix7 = (name) =>
  `https://cdn.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/${name}/${name}.yaml`;

const ruleProviders = {
  proxydns: {
    ...ruleProviderYaml,
    behavior: "classical",
    url: "https://raw.githubusercontent.com/Shattered217/ownrule-clash/main/dns.yaml",
    path: "./rulesets/custom/proxydns.yaml",
  },

  // Loyalsoldier 基础规则：负责国内直连、局域网、常见代理域名和兜底分流。
  reject_ls: {
    ...ruleProviderYaml,
    behavior: "domain",
    url: loyal("reject"),
    path: "./rulesets/loyalsoldier/reject.yaml",
  },
  applications_ls: {
    ...ruleProviderYaml,
    behavior: "classical",
    url: loyal("applications"),
    path: "./rulesets/loyalsoldier/applications.yaml",
  },
  private_ls: {
    ...ruleProviderYaml,
    behavior: "domain",
    url: loyal("private"),
    path: "./rulesets/loyalsoldier/private.yaml",
  },
  direct_ls: {
    ...ruleProviderYaml,
    behavior: "domain",
    url: loyal("direct"),
    path: "./rulesets/loyalsoldier/direct.yaml",
  },
  proxy_ls: {
    ...ruleProviderYaml,
    behavior: "domain",
    url: loyal("proxy"),
    path: "./rulesets/loyalsoldier/proxy.yaml",
  },
  gfw_ls: {
    ...ruleProviderYaml,
    behavior: "domain",
    url: loyal("gfw"),
    path: "./rulesets/loyalsoldier/gfw.yaml",
  },
  tld_not_cn_ls: {
    ...ruleProviderYaml,
    behavior: "domain",
    url: loyal("tld-not-cn"),
    path: "./rulesets/loyalsoldier/tld-not-cn.yaml",
  },
  telegramcidr_ls: {
    ...ruleProviderYaml,
    behavior: "ipcidr",
    url: loyal("telegramcidr"),
    path: "./rulesets/loyalsoldier/telegramcidr.yaml",
  },
  cncidr_ls: {
    ...ruleProviderYaml,
    behavior: "ipcidr",
    url: loyal("cncidr"),
    path: "./rulesets/loyalsoldier/cncidr.yaml",
  },
  lancidr_ls: {
    ...ruleProviderYaml,
    behavior: "ipcidr",
    url: loyal("lancidr"),
    path: "./rulesets/loyalsoldier/lancidr.yaml",
  },
  icloud_ls: {
    ...ruleProviderYaml,
    behavior: "domain",
    url: loyal("icloud"),
    path: "./rulesets/loyalsoldier/icloud.yaml",
  },
  apple_ls: {
    ...ruleProviderYaml,
    behavior: "domain",
    url: loyal("apple"),
    path: "./rulesets/loyalsoldier/apple.yaml",
  },
  google_ls: {
    ...ruleProviderYaml,
    behavior: "domain",
    url: loyal("google"),
    path: "./rulesets/loyalsoldier/google.yaml",
  },

  // blackmatrix7 AI 规则：负责 OpenAI/Codex、Claude、Gemini、Copilot。
  openai_bm7: {
    ...ruleProviderYaml,
    behavior: "classical",
    url: blackmatrix7("OpenAI"),
    path: "./rulesets/blackmatrix7/openai.yaml",
  },
  copilot_bm7: {
    ...ruleProviderYaml,
    behavior: "classical",
    url: blackmatrix7("Copilot"),
    path: "./rulesets/blackmatrix7/copilot.yaml",
  },
  claude_bm7: {
    ...ruleProviderYaml,
    behavior: "classical",
    url: blackmatrix7("Claude"),
    path: "./rulesets/blackmatrix7/claude.yaml",
  },
  gemini_bm7: {
    ...ruleProviderYaml,
    behavior: "classical",
    url: blackmatrix7("Gemini"),
    path: "./rulesets/blackmatrix7/gemini.yaml",
  },

  // MetaCubeX 细分服务规则。
  github: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("github"),
    path: "./rulesets/metacubex/github.mrs",
  },
  bing: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("bing"),
    path: "./rulesets/metacubex/bing.mrs",
  },
  onedrive: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("onedrive"),
    path: "./rulesets/metacubex/onedrive.mrs",
  },
  microsoft: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("microsoft"),
    path: "./rulesets/metacubex/microsoft.mrs",
  },
  speedtest: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("speedtest"),
    path: "./rulesets/metacubex/speedtest.mrs",
  },
  adobe: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("adobe"),
    path: "./rulesets/metacubex/adobe.mrs",
  },
  youtube: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("youtube"),
    path: "./rulesets/metacubex/youtube.mrs",
  },
  netflix_ip: {
    ...ruleProviderMrs,
    behavior: "ipcidr",
    url: metaGeoip("netflix"),
    path: "./rulesets/metacubex/netflix-ip.mrs",
  },
  netflix_site: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("netflix"),
    path: "./rulesets/metacubex/netflix-site.mrs",
  },
  pornhub: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("pornhub"),
    path: "./rulesets/metacubex/pornhub.mrs",
  },
  bilibili: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("bilibili"),
    path: "./rulesets/metacubex/bilibili.mrs",
  },
  spotify: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("spotify"),
    path: "./rulesets/metacubex/spotify.mrs",
  },
  tiktok: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("tiktok"),
    path: "./rulesets/metacubex/tiktok.mrs",
  },
  proxy_meta: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeositeLite("proxy"),
    path: "./rulesets/metacubex/proxy.mrs",
  },
  cn_meta: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("cn"),
    path: "./rulesets/metacubex/cn.mrs",
  },
  private_meta: {
    ...ruleProviderMrs,
    behavior: "ipcidr",
    url: metaGeoip("private"),
    path: "./rulesets/metacubex/private.mrs",
  },
  gfw_meta: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("gfw"),
    path: "./rulesets/metacubex/gfw.mrs",
  },
  tld_not_cn_meta: {
    ...ruleProviderMrs,
    behavior: "domain",
    url: metaGeosite("tld-!cn"),
    path: "./rulesets/metacubex/tld-not-cn.mrs",
  },
  telegramcidr_meta: {
    ...ruleProviderMrs,
    behavior: "ipcidr",
    url: metaGeoip("telegram"),
    path: "./rulesets/metacubex/telegramcidr.mrs",
  },
  cncidr_meta: {
    ...ruleProviderMrs,
    behavior: "ipcidr",
    url: metaGeoip("cn"),
    path: "./rulesets/metacubex/cncidr.mrs",
  },
  lancidr_meta: {
    ...ruleProviderMrs,
    behavior: "ipcidr",
    url: metaGeoipLite("private"),
    path: "./rulesets/metacubex/lancidr.mrs",
  },

  gamedl: {
    type: "http",
    behavior: "classical",
    format: "text",
    interval: 86400,
    url: "https://raw.githubusercontent.com/ACL4SSR/ACL4SSR/master/Clash/Ruleset/GameDownload.list",
    path: "./rulesets/games/gamedl.list",
  },
  ubisoft: {
    type: "http",
    behavior: "classical",
    format: "text",
    interval: 86400,
    url: "https://raw.githubusercontent.com/Shattered217/ownrule-clash/main/ubisoft.list",
    path: "./rulesets/games/ubisoft.list",
  },
  epic: {
    type: "http",
    behavior: "classical",
    format: "text",
    interval: 86400,
    url: "https://raw.githubusercontent.com/ACL4SSR/ACL4SSR/master/Clash/Ruleset/Epic.list",
    path: "./rulesets/games/epic.list",
  },
  ea: {
    type: "http",
    behavior: "classical",
    format: "text",
    interval: 86400,
    url: "https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Clash/EA/EA.list",
    path: "./rulesets/games/ea.list",
  },
  steamCN: {
    type: "http",
    behavior: "classical",
    format: "text",
    interval: 86400,
    url: "https://raw.githubusercontent.com/Shattered217/ownrule-clash/main/steam-CN.list",
    path: "./rulesets/games/steamCN.list",
  },
  steam: {
    type: "http",
    behavior: "classical",
    format: "text",
    interval: 86400,
    url: "https://raw.githubusercontent.com/ACL4SSR/ACL4SSR/master/Clash/Ruleset/Steam.list",
    path: "./rulesets/games/steam.list",
  },
};

const domesticDirectRules = [
  "PROCESS-NAME,Xshell.exe,全局直连",
  "PROCESS-NAME,Xftp.exe,全局直连",
  "PROCESS-NAME,filezilla.exe,全局直连",
  "PROCESS-NAME,MobaXterm.exe,全局直连",
  "PROCESS-NAME,WeChat.exe,全局直连",
  "PROCESS-NAME,Weixin.exe,全局直连",
  "PROCESS-NAME,WXWork.exe,全局直连",
  "PROCESS-NAME,QQ.exe,全局直连",
  "PROCESS-NAME,TIM.exe,全局直连",
  "PROCESS-NAME,QQProtect.exe,全局直连",
  "PROCESS-NAME-WILDCARD,*Xshell*,全局直连",
  "PROCESS-NAME-WILDCARD,*Xftp*,全局直连",
  "PROCESS-NAME-WILDCARD,*MobaXterm*,全局直连",
  "PROCESS-NAME-WILDCARD,*WeChat*,全局直连",
  "PROCESS-NAME-WILDCARD,*Weixin*,全局直连",
  "PROCESS-NAME-WILDCARD,*FileZilla*,全局直连",
  "PROCESS-NAME-WILDCARD,*绿鲸*,全局直连",
  "PROCESS-NAME-WILDCARD,*xiaolvjing*,全局直连",
  "PROCESS-NAME,tailscaled.exe,全局直连",
  "PROCESS-NAME,tailscale.exe,全局直连",

  "PROCESS-NAME,navicat.exe,全局直连",
  "PROCESS-NAME-WILDCARD,*Navicat*,全局直连",
  "PROCESS-NAME,BaiduNetdisk.exe,全局直连",
  "PROCESS-NAME,baidunetdisk.exe,全局直连",
  "PROCESS-NAME,baidunetdiskhost.exe,全局直连",
  "PROCESS-NAME,YunDetectService.exe,全局直连",
  "PROCESS-NAME,QuarkCloudDrive.exe,全局直连",
  "PROCESS-NAME-WILDCARD,*Quark*,全局直连",
  "PROCESS-NAME,cloudmusic.exe,全局直连",
  "PROCESS-NAME,NeteaseMusic.exe,全局直连",
  "PROCESS-NAME,KwMusic.exe,全局直连",
  "PROCESS-NAME,KuwoMusic.exe,全局直连",
  "PROCESS-NAME-WILDCARD,*Moekoe*,全局直连",
  "PROCESS-NAME,ToDesk.exe,全局直连",
  "PROCESS-NAME,ToDesk_Service.exe,全局直连",
  "PROCESS-NAME,rustdesk.exe,全局直连",
  "PROCESS-NAME,RustDesk.exe,全局直连",
  "PROCESS-NAME,wemeetapp.exe,全局直连",
  "PROCESS-NAME,WeMeetApp.exe,全局直连",
  "PROCESS-NAME,TencentMeeting.exe,全局直连",
  "PROCESS-NAME,DingTalk.exe,全局直连",
  "PROCESS-NAME,DingTalkLite.exe,全局直连",
  "PROCESS-NAME,Trae.exe,全局直连",
  "PROCESS-NAME,trae.exe,全局直连",

  "DOMAIN-SUFFIX,qq.com,全局直连",
  "DOMAIN-SUFFIX,weixin.qq.com,全局直连",
  "DOMAIN-SUFFIX,wechat.com,全局直连",
  "DOMAIN-SUFFIX,qpic.cn,全局直连",
  "DOMAIN-SUFFIX,gtimg.cn,全局直连",
  "DOMAIN-SUFFIX,gtimg.com,全局直连",
  "DOMAIN-SUFFIX,myqcloud.com,全局直连",

  "DOMAIN-SUFFIX,csdn.net,全局直连",
  "DOMAIN-SUFFIX,csdnimg.cn,全局直连",
  "DOMAIN-SUFFIX,cnblogs.com,全局直连",
  "DOMAIN-SUFFIX,zhihu.com,全局直连",
  "DOMAIN-SUFFIX,zhimg.com,全局直连",
  "DOMAIN-SUFFIX,zwu.edu.cn,全局直连",

  "DOMAIN,api.deepseek.com,全局直连",
  "DOMAIN-SUFFIX,deepseek.com,全局直连",
  "DOMAIN-SUFFIX,deepseek.ai,全局直连",
  "DOMAIN-SUFFIX,trae.cn,全局直连",
  "DOMAIN-SUFFIX,doubao.com,全局直连",
  "DOMAIN-SUFFIX,volcengine.com,全局直连",
  "DOMAIN-SUFFIX,volces.com,全局直连",
  "DOMAIN-SUFFIX,byteimg.com,全局直连",
  "DOMAIN-SUFFIX,bytedance.com,全局直连",

  "DOMAIN-SUFFIX,baidu.com,全局直连",
  "DOMAIN-SUFFIX,baidupcs.com,全局直连",
  "DOMAIN-SUFFIX,baidustatic.com,全局直连",
  "DOMAIN-SUFFIX,bdstatic.com,全局直连",
  "DOMAIN-SUFFIX,quark.cn,全局直连",
  "DOMAIN-SUFFIX,myquark.cn,全局直连",
  "DOMAIN-SUFFIX,uc.cn,全局直连",
  "DOMAIN-SUFFIX,ucweb.com,全局直连",
  "DOMAIN-SUFFIX,music.163.com,全局直连",
  "DOMAIN-SUFFIX,kuwo.cn,全局直连",
  "DOMAIN-SUFFIX,kuwo.com,全局直连",
  "DOMAIN-SUFFIX,todesk.com,全局直连",
  "DOMAIN-SUFFIX,rustdesk.com,全局直连",
  "DOMAIN-SUFFIX,meeting.tencent.com,全局直连",
  "DOMAIN-SUFFIX,wemeet.qq.com,全局直连",
  "DOMAIN-SUFFIX,dingtalk.com,全局直连",
  "DOMAIN-SUFFIX,dingtalkapps.com,全局直连",
];

const codexRules = [
  "PROCESS-NAME,codex.exe,Codex",
  "PROCESS-NAME,cockpit-tools.exe,Codex",
  "PROCESS-NAME,ChatGPT.exe,Codex",
  "PROCESS-NAME,Cursor.exe,Codex",
  "PROCESS-NAME,cursor.exe,Codex",

  "DOMAIN-SUFFIX,chatgpt.com,Codex",
  "DOMAIN-SUFFIX,openai.com,Codex",
  "DOMAIN-SUFFIX,oaistatic.com,Codex",
  "DOMAIN-SUFFIX,oaiusercontent.com,Codex",
  "DOMAIN-SUFFIX,featuregates.org,Codex",
  "DOMAIN-SUFFIX,statsig.com,Codex",
  "DOMAIN-SUFFIX,statsigapi.net,Codex",
  "DOMAIN-SUFFIX,intercom.io,Codex",
  "DOMAIN-SUFFIX,intercomcdn.com,Codex",
  "DOMAIN-SUFFIX,workos.com,Codex",
  "DOMAIN-SUFFIX,workoscdn.com,Codex",
  "DOMAIN,challenges.cloudflare.com,Codex",
  "DOMAIN,ws.chatgpt.com,Codex",

  "DOMAIN-SUFFIX,cursor.sh,Codex",
  "DOMAIN-SUFFIX,cursor.com,Codex",
  "DOMAIN-SUFFIX,cursor-cdn.com,Codex",
  "DOMAIN-SUFFIX,cursorapi.com,Codex",
  "DOMAIN,anysphere-binaries.s3.us-east-1.amazonaws.com,Codex",

  "RULE-SET,openai_bm7,Codex",
  "RULE-SET,copilot_bm7,Codex",
  "RULE-SET,claude_bm7,Codex",
];

const paymentRules = [
  "DOMAIN-SUFFIX,paypal.com,节点选择",
  "DOMAIN-SUFFIX,paypalobjects.com,节点选择",
  "DOMAIN-SUFFIX,paypal.me,节点选择",
  "DOMAIN-SUFFIX,pypl.com,节点选择",
  "DOMAIN-SUFFIX,stripe.com,节点选择",
  "DOMAIN-SUFFIX,stripe.network,节点选择",
  "DOMAIN-SUFFIX,braintreegateway.com,节点选择",
  "DOMAIN-SUFFIX,braintree-api.com,节点选择",
  "DOMAIN-SUFFIX,braintreepayments.com,节点选择",
];

const geminiRules = [
  "PROCESS-NAME,Antigravity.exe,Gemini",
  "PROCESS-NAME,antigravity.exe,Gemini",
  "PROCESS-NAME-WILDCARD,*Antigravity*,Gemini",
  "PROCESS-NAME-WILDCARD,*antigravity*,Gemini",

  "DOMAIN-SUFFIX,antigravity.google,Gemini",
  "DOMAIN,accounts.google.com,Gemini",
  "DOMAIN,oauth2.googleapis.com,Gemini",
  "DOMAIN,gemini.google.com,Gemini",
  "DOMAIN,bard.google.com,Gemini",
  "DOMAIN,ai.google.dev,Gemini",
  "DOMAIN,aistudio.google.com,Gemini",
  "DOMAIN,makersuite.google.com,Gemini",
  "DOMAIN-SUFFIX,generativelanguage.googleapis.com,Gemini",
  "DOMAIN-SUFFIX,googleapis.com,Gemini",
  "DOMAIN-SUFFIX,gstatic.com,Gemini",
  "DOMAIN-SUFFIX,googleusercontent.com,Gemini",
  "RULE-SET,gemini_bm7,Gemini",
];

const developerProxyRules = [
  "DOMAIN-SUFFIX,docker.io,节点选择",
  "DOMAIN-SUFFIX,docker.com,节点选择",
  "DOMAIN-SUFFIX,dockerhub.com,节点选择",
  "DOMAIN,registry-1.docker.io,节点选择",
  "DOMAIN,auth.docker.io,节点选择",
  "DOMAIN,production.cloudflare.docker.com,节点选择",
  "DOMAIN-SUFFIX,ghcr.io,节点选择",
  "DOMAIN-SUFFIX,quay.io,节点选择",
  "DOMAIN-SUFFIX,gcr.io,节点选择",
  "DOMAIN-SUFFIX,pkg.dev,节点选择",
  "DOMAIN-SUFFIX,k8s.gcr.io,节点选择",
  "DOMAIN-SUFFIX,mcr.microsoft.com,节点选择",

  "DOMAIN-SUFFIX,jetbrains.com,节点选择",
  "DOMAIN-SUFFIX,plugins.jetbrains.com,节点选择",
  "DOMAIN-SUFFIX,download.jetbrains.com,节点选择",
  "DOMAIN-SUFFIX,download-cdn.jetbrains.com,节点选择",

  "DOMAIN-SUFFIX,postman.com,节点选择",
  "DOMAIN-SUFFIX,getpostman.com,节点选择",
  "DOMAIN-SUFFIX,postman.co,节点选择",

  "DOMAIN-SUFFIX,repo.maven.apache.org,节点选择",
  "DOMAIN-SUFFIX,repo1.maven.org,节点选择",
  "DOMAIN-SUFFIX,maven.org,节点选择",
  "DOMAIN-SUFFIX,gradle.org,节点选择",
  "DOMAIN-SUFFIX,services.gradle.org,节点选择",
  "DOMAIN-SUFFIX,plugins.gradle.org,节点选择",
  "DOMAIN-SUFFIX,trae.ai,节点选择",
];

const rules = [
  ...domesticDirectRules,
  ...codexRules,
  ...paymentRules,
  ...geminiRules,
  ...developerProxyRules,

  "RULE-SET,proxydns,全局直连",
  "RULE-SET,reject_ls,REJECT",

  "RULE-SET,speedtest,全局直连",
  "RULE-SET,github,节点选择",
  "RULE-SET,bing,全局直连",
  "RULE-SET,onedrive,全局直连",
  "RULE-SET,microsoft,全局直连",
  "RULE-SET,icloud_ls,全局直连",
  "RULE-SET,apple_ls,全局直连",
  "RULE-SET,google_ls,Gemini",
  "RULE-SET,adobe,全局直连",
  "RULE-SET,pornhub,节点选择",
  "RULE-SET,bilibili,全局直连",
  "RULE-SET,youtube,节点选择",
  "RULE-SET,tiktok,节点选择",
  "RULE-SET,netflix_ip,节点选择,no-resolve",
  "RULE-SET,netflix_site,节点选择",
  "RULE-SET,spotify,节点选择",

  "RULE-SET,gamedl,全局直连",
  "RULE-SET,ubisoft,节点选择",
  "RULE-SET,epic,节点选择",
  "RULE-SET,ea,节点选择",
  "RULE-SET,steamCN,全局直连",
  "RULE-SET,steam,节点选择",

  "RULE-SET,applications_ls,全局直连",
  "RULE-SET,private_ls,全局直连",
  "RULE-SET,private_meta,全局直连,no-resolve",
  "RULE-SET,direct_ls,全局直连",
  "RULE-SET,cn_meta,全局直连",
  "RULE-SET,lancidr_ls,全局直连,no-resolve",
  "RULE-SET,lancidr_meta,全局直连,no-resolve",
  "RULE-SET,cncidr_ls,全局直连,no-resolve",
  "RULE-SET,cncidr_meta,全局直连,no-resolve",

  "RULE-SET,telegramcidr_ls,节点选择,no-resolve",
  "RULE-SET,telegramcidr_meta,节点选择,no-resolve",
  "RULE-SET,proxy_ls,节点选择",
  "RULE-SET,proxy_meta,节点选择",
  "RULE-SET,gfw_ls,节点选择",
  "RULE-SET,gfw_meta,节点选择",
  "RULE-SET,tld_not_cn_ls,节点选择",
  "RULE-SET,tld_not_cn_meta,节点选择",

  "GEOIP,LAN,全局直连,no-resolve",
  "GEOIP,CN,全局直连,no-resolve",
  "MATCH,漏网之鱼",
];

const groupBaseOption = {
  interval: 300,
  timeout: 3000,
  url: testUrl,
  lazy: false,
  "max-failed-times": 3,
  hidden: false,
};

const regionCode = (...codes) =>
  `(^|[^A-Za-z])(${codes.join("|")})[0-9]*([^A-Za-z]|$)`;

const jpFilter = [
  regionCode("JP", "JPN"),
  "日本",
  "东京",
  "東京",
  "大阪",
  "Japan",
  "Tokyo",
  "Osaka",
  "🇯🇵",
].join("|");

const usFilter = [
  regionCode("US", "USA"),
  "美国",
  "美國",
  "美西",
  "美东",
  "美東",
  "美国西部",
  "美国东部",
  "洛杉矶",
  "洛杉磯",
  "硅谷",
  "圣何塞",
  "聖何塞",
  "西雅图",
  "西雅圖",
  "纽约",
  "紐約",
  "Ashburn",
  "Virginia",
  "Los Angeles",
  "Seattle",
  "New York",
  "United States",
  "America",
  "🇺🇸",
].join("|");

const sgFilter = [
  regionCode("SG"),
  "新加坡",
  "狮城",
  "獅城",
  "Singapore",
  "🇸🇬",
].join("|");

const usSgFilter = [usFilter, sgFilter].join("|");

const nodeFilter = [
  jpFilter,
  usFilter,
  sgFilter,
  regionCode("HK", "HKG"),
  "香港",
  "Hong Kong",
  "🇭🇰",
  regionCode("TW", "TWN"),
  "台湾",
  "台灣",
  "Taiwan",
  "🇹🇼",
  regionCode("KR", "KOR"),
  "韩国",
  "韓國",
  "Korea",
  "Seoul",
  "🇰🇷",
  regionCode("DE", "GER"),
  "德国",
  "德國",
  "Germany",
  "🇩🇪",
  regionCode("NL"),
  "荷兰",
  "荷蘭",
  "Netherlands",
  "🇳🇱",
  regionCode("GB", "UK"),
  "英国",
  "英國",
  "United Kingdom",
  "Britain",
  "🇬🇧",
  regionCode("CA"),
  "加拿大",
  "Canada",
  "🇨🇦",
  regionCode("AU"),
  "澳大利亚",
  "澳洲",
  "Australia",
  "🇦🇺",
  regionCode("FR"),
  "法国",
  "法國",
  "France",
  "🇫🇷",
  "专线",
  "專線",
  "中转",
  "中轉",
  "IEPL",
  "IPLC",
  "解锁",
  "Hysteria",
  "Hysteria2",
  "HY2",
  "Vless",
  "Trojan",
  "Tuic",
  "SS",
].join("|");

const proxyGroups = [
  {
    ...groupBaseOption,
    name: "节点选择",
    type: "select",
    proxies: ["自动选择", "美国节点", "手动选择", "故障转移", "DIRECT"],
    icon: "https://fastly.jsdelivr.net/gh/Koolson/Qure@master/IconSet/Color/Airport.png",
  },
  {
    ...groupBaseOption,
    name: "Codex",
    type: "select",
    proxies: [
      "节点选择",
      "Codex-日本自动",
      "美国节点",
      "手动选择",
      "自动选择",
      "故障转移",
      "DIRECT",
    ],
    icon: "https://www.clashverge.dev/assets/icons/chatgpt.svg",
  },
  {
    ...groupBaseOption,
    name: "Codex-日本自动",
    type: "url-test",
    tolerance: 80,
    "include-all": true,
    filter: jpFilter,
    icon: "https://fastly.jsdelivr.net/gh/clash-verge-rev/clash-verge-rev.github.io@main/docs/assets/icons/flags/jp.svg",
  },
  {
    ...groupBaseOption,
    name: "Gemini",
    type: "select",
    proxies: [
      "节点选择",
      "Gemini-美新自动",
      "美国节点",
      "手动选择",
      "自动选择",
      "故障转移",
      "DIRECT",
    ],
    icon: "https://www.gstatic.com/lamda/images/gemini_sparkle_aurora_33f86dc0c0257da337c63.svg",
  },
  {
    ...groupBaseOption,
    name: "Gemini-美新自动",
    type: "url-test",
    tolerance: 80,
    "include-all": true,
    filter: usSgFilter,
    icon: "https://www.gstatic.com/lamda/images/gemini_sparkle_aurora_33f86dc0c0257da337c63.svg",
  },
  {
    ...groupBaseOption,
    name: "美国节点",
    type: "select",
    proxies: ["美国自动", "手动选择", "自动选择", "故障转移", "DIRECT"],
    icon: "https://fastly.jsdelivr.net/gh/clash-verge-rev/clash-verge-rev.github.io@main/docs/assets/icons/flags/us.svg",
  },
  {
    ...groupBaseOption,
    name: "美国自动",
    type: "url-test",
    tolerance: 80,
    "include-all": true,
    filter: usFilter,
    icon: "https://fastly.jsdelivr.net/gh/clash-verge-rev/clash-verge-rev.github.io@main/docs/assets/icons/flags/us.svg",
  },
  {
    ...groupBaseOption,
    name: "自动选择",
    type: "url-test",
    tolerance: 100,
    "include-all": true,
    filter: nodeFilter,
    icon: "https://fastly.jsdelivr.net/gh/clash-verge-rev/clash-verge-rev.github.io@main/docs/assets/icons/speed.svg",
  },
  {
    ...groupBaseOption,
    name: "手动选择",
    type: "select",
    "include-all": true,
    icon: "https://fastly.jsdelivr.net/gh/clash-verge-rev/clash-verge-rev.github.io@main/docs/assets/icons/adjust.svg",
  },
  {
    ...groupBaseOption,
    name: "故障转移",
    type: "fallback",
    "include-all": true,
    filter: nodeFilter,
    icon: "https://fastly.jsdelivr.net/gh/clash-verge-rev/clash-verge-rev.github.io@main/docs/assets/icons/ambulance.svg",
  },
  {
    ...groupBaseOption,
    name: "全局直连",
    type: "select",
    proxies: ["DIRECT"],
    icon: "https://fastly.jsdelivr.net/gh/clash-verge-rev/clash-verge-rev.github.io@main/docs/assets/icons/link.svg",
  },
  {
    ...groupBaseOption,
    name: "漏网之鱼",
    type: "select",
    proxies: ["节点选择", "美国节点", "自动选择", "手动选择", "故障转移", "全局直连"],
    icon: "https://fastly.jsdelivr.net/gh/clash-verge-rev/clash-verge-rev.github.io@main/docs/assets/icons/fish.svg",
  },
];

function main(config) {
  const proxyCount = config?.proxies?.length ?? 0;
  const proxyProviderCount =
    typeof config?.["proxy-providers"] === "object"
      ? Object.keys(config["proxy-providers"]).length
      : 0;

  if (proxyCount === 0 && proxyProviderCount === 0) {
    throw new Error("配置文件中未找到任何代理");
  }

  config["mode"] = "rule";
  config["find-process-mode"] = "always";
  config["unified-delay"] = true;
  config["tcp-concurrent"] = true;
  config["geodata-loader"] = "standard";
  config["geosite-matcher"] = "mph";
  config["global-ua"] = "clash.meta";

  config["profile"] = {
    "store-selected": true,
    "store-fake-ip": true,
  };

  config["sniffer"] = {
    enable: true,
    "force-dns-mapping": true,
    "parse-pure-ip": true,
    "override-destination": true,
    sniff: {
      TLS: {
        ports: [443, 8443],
      },
      HTTP: {
        ports: [80, "8080-8880"],
        "override-destination": true,
      },
      QUIC: {
        ports: [443, 8443],
      },
    },
    "skip-domain": [
      "Mijia Cloud",
      "+.oray.com",
      "+.lan",
      "+.local",
      "+.msftconnecttest.com",
      "+.msftncsi.com",
    ],
  };

  config["dns"] = dnsConfig;
  config["rule-providers"] = ruleProviders;
  config["proxy-groups"] = proxyGroups;
  config["rules"] = rules;

  return config;
}
