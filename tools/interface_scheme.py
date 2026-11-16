"""Draw an automaton interface ("схема связей") in the style of the previous
article: the automaton is a column with input ids on the left and output ids
on the right; sources are boxes on the left, receivers on the right, each
with one line per input/output.

    python interface_scheme.py spec.json out_dir

spec.json: {"<file stem>": {"name": "A0",
                            "inputs":  [["Среда", [["e01", "Нажата кнопка «Игра»"], ...]], ...],
                            "outputs": [["A2", [["e0", "Начало игры"], ...]], ...]}, ...}
Writes <out_dir>/<file stem>.png (rendered with headless Chromium).
"""

import html
import json
import os
import subprocess
import sys
import tempfile

CSS = """
body { margin: 0; background: #fff; }
.s { display: inline-grid; padding: 6px; font-family: 'Liberation Serif', 'Times New Roman', serif;
     font-size: 15px; color: #000; grid-auto-rows: 24px; column-gap: 0; background: #fff; }
.s > div { display: flex; align-items: center; white-space: nowrap; box-sizing: border-box; }
.g { justify-content: center; border: 1px solid #000; padding: 0 14px; min-width: 70px; }
.dl { justify-content: flex-end; padding: 0 8px 0 18px; border-bottom: 1px solid #000; }
.dr { justify-content: flex-start; padding: 0 18px 0 8px; border-bottom: 1px solid #000; }
.first { border-top: 1px solid #000; }
.il { justify-content: flex-start; padding: 0 10px 0 10px; }
.ir { justify-content: flex-end; padding: 0 10px 0 10px; }
.box { border: 1px solid #000; z-index: 0; }
.hd { justify-content: center; border-bottom: 1px solid #000; }
"""


def esc(s):
    return html.escape(s, quote=False)


def layout(groups):
    """Return [(group, first_row, items)] with one blank row between groups."""
    out, row = [], 0
    for name, items in groups:
        out.append((name, row, items))
        row += len(items) + 1
    return out, max(row - 1, 0)


def page(spec):
    left, nl = layout(spec["inputs"])
    right, nr = layout(spec["outputs"])
    n = max(nl, nr)
    cells = []

    def add(cls, col, row, span=1, text="", colspan=1):
        cells.append('<div class="%s" style="grid-column:%d/span %d;grid-row:%d/span %d">%s</div>'
                     % (cls, col, colspan, row, span, esc(text)))

    # header row = 1, items start at row 2
    add("box", 3, 1, span=n + 1, colspan=2)
    add("hd", 3, 1, text=spec["name"], colspan=2)
    for name, r0, items in left:
        add("g", 1, r0 + 2, span=len(items), text=name)
        for k, (ident, desc) in enumerate(items):
            add("dl" + (" first" if k == 0 else ""), 2, r0 + 2 + k, text=desc)
            add("il", 3, r0 + 2 + k, text=ident)
    for name, r0, items in right:
        add("g", 6, r0 + 2, span=len(items), text=name)
        for k, (ident, desc) in enumerate(items):
            add("dr" + (" first" if k == 0 else ""), 5, r0 + 2 + k, text=desc)
            add("ir", 4, r0 + 2 + k, text=ident)
    return ('<!doctype html><meta charset="utf-8"><style>%s</style>'
            '<div class="s" id="s" style="grid-template-columns:auto auto auto auto auto auto">%s</div>'
            % (CSS, "".join(cells)))


SHOT = """
const { chromium } = require('playwright');
(async () => {
  const jobs = JSON.parse(process.argv[2]);
  const b = await chromium.launch();
  const p = await b.newPage({ deviceScaleFactor: 3 });
  for (const [src, dst] of jobs) {
    await p.goto('file://' + src);
    await (await p.$('#s')).screenshot({ path: dst });
  }
  await b.close();
})();
"""


def render(specs, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    tmp = tempfile.mkdtemp()
    jobs = []
    for stem, spec in specs.items():
        src = os.path.join(tmp, stem + ".html")
        with open(src, "w", encoding="utf-8") as f:
            f.write(page(spec))
        jobs.append([src, os.path.abspath(os.path.join(out_dir, stem + ".png"))])
    js = os.path.join(tmp, "shot.js")
    with open(js, "w") as f:
        f.write(SHOT)
    root = subprocess.run(["npm", "root", "-g"], capture_output=True, text=True).stdout.strip()
    env = dict(os.environ, NODE_PATH=root)
    subprocess.run(["node", js, json.dumps(jobs)], check=True, env=env)
    return [j[1] for j in jobs]


if __name__ == "__main__":
    with open(sys.argv[1], encoding="utf-8") as f:
        print("\n".join(render(json.load(f), sys.argv[2])))
