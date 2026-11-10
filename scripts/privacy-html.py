#!/usr/bin/env python3
"""把 docs/privacy/privacy.md 转成静态页面 docs/privacy/privacy.html（应用商店要求隐私政策网址是静态 HTML）。
只认这份文件用到的几种写法：# 标题、## 小标题、- 列表、**加粗**、普通段落。"""
import html, re, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
md = (root / "docs/privacy/privacy.md").read_text(encoding="utf-8")

def inline(s):
    s = html.escape(s)
    s = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", s)
    return re.sub(r"(https?://[^\s<]+)", r'<a href="\1">\1</a>', s)

out, in_list, title = [], False, "隐私政策"
for line in md.splitlines():
    if not line.startswith("- ") and in_list:
        out.append("</ul>"); in_list = False
    if line.startswith("# "):
        title = line[2:].strip(); out.append(f"<h1>{inline(title)}</h1>")
    elif line.startswith("## "):
        out.append(f"<h2>{inline(line[3:])}</h2>")
    elif line.startswith("- "):
        if not in_list: out.append("<ul>"); in_list = True
        out.append(f"<li>{inline(line[2:])}</li>")
    elif line.strip():
        out.append(f"<p>{inline(line)}</p>")
if in_list: out.append("</ul>")

page = f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<style>
body{{margin:0;background:#fff;color:#17181c;font:16px/1.75 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif}}
main{{max-width:760px;margin:0 auto;padding:24px 16px 48px}}
h1{{font-size:26px;margin:8px 0 16px}} h2{{font-size:19px;margin:28px 0 8px}}
p,li{{margin:6px 0}} ul{{padding-left:22px}} a{{color:#2e5bff;word-break:break-all}}
</style>
</head>
<body>
<main>
{chr(10).join(out)}
</main>
</body>
</html>
"""
(root / "docs/privacy/privacy.html").write_text(page, encoding="utf-8")
print("docs/privacy/privacy.html")
