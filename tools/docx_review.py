"""Propose edits to a .docx as tracked changes and comments.

Usage as a library (a round of edits is a small script):

    from docx_review import Doc
    d = Doc("in.docx", author="Claude")
    d.replace("В предыдущей работе", "метод валидации", "подход к валидации")
    d.rewrite("Состав этапов", "Новый текст абзаца целиком.")
    d.insert_after("Для системы из более", "Новый абзац.", like="Для системы из более")
    d.comment("Валидация", "Вопрос автору", anchor="Валидация")
    d.scale_image("Диаграмма состояний автомата A2", 0.7)
    d.replace_image("Диаграмма состояний автомата A4", "a4.png", width_cm=8)
    d.save("out.docx")

CLI:
    python docx_review.py dump in.docx            # numbered paragraphs
    python docx_review.py render in.docx outdir   # PDF + page JPEGs (changes accepted)
    python docx_review.py changes in.docx         # tracked edits as [-old-] {+new+}
    python docx_review.py verify orig.docx edited.docx  # rejecting all edits == original?

Nothing outside tracked changes and comments is modified: untouched
paragraphs are left as they are, and `verify` checks that rejecting every
proposal gives back the original document text and structure.

A paragraph is addressed by a text snippet that must occur in exactly one
paragraph (as it reads before this round's edits). Edits are word-level:
rewrite() diffs old and new text and marks only the changed words. Requires
lxml. Rendering uses LibreOffice (soffice) and pdftoppm.
"""

import copy
import datetime
import difflib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

from lxml import etree

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
A = "http://schemas.openxmlformats.org/drawingml/2006/main"
PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
CT = "http://schemas.openxmlformats.org/package/2006/content-types"
NS = {"w": W, "r": R, "wp": WP, "a": A, "pic": PIC}
EMU_PER_CM = 360000


def q(tag):
    prefix, name = tag.split(":")
    return "{%s}%s" % (NS[prefix], name)


def _tokens(text):
    return re.findall(r"\w+|\s+|[^\w\s]", text)


def _merged_opcodes(old_tok, new_tok):
    """Word diff where changes separated only by whitespace or a lone short word
    are merged into one deletion + one insertion, so redlines read as phrases."""
    ops = difflib.SequenceMatcher(None, old_tok, new_tok, autojunk=False).get_opcodes()

    def weak(op):
        _, i1, i2, _, _ = op
        toks = old_tok[i1:i2]
        words = [t for t in toks if not t.isspace()]
        return all(t.isspace() for t in toks) or (len(words) == 1 and len(words[0]) <= 3)

    merged = []
    for op in ops:
        merged.append(list(op))
    changed = True
    while changed:
        changed = False
        for k in range(1, len(merged) - 1):
            if merged[k][0] == "equal" and merged[k - 1][0] != "equal" and merged[k + 1][0] != "equal" \
                    and weak(merged[k]):
                a, b, c = merged[k - 1], merged[k], merged[k + 1]
                merged[k - 1:k + 2] = [["replace", a[1], c[2], a[3], c[4]]]
                changed = True
                break
        for k in range(len(merged) - 1):
            if merged[k][0] != "equal" and merged[k + 1][0] != "equal":
                a, c = merged[k], merged[k + 1]
                merged[k:k + 2] = [["replace", a[1], c[2], a[3], c[4]]]
                changed = True
                break
    return [tuple(op) for op in merged]


class Doc:
    def __init__(self, path, author="Claude", date=None):
        self.author = author
        self.date = date or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.dir = tempfile.mkdtemp(prefix="docx_review_")
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                name = os.path.normpath(info.filename)
                if name.startswith("..") or os.path.isabs(name):
                    raise ValueError("unsafe path in archive: %s" % info.filename)
                z.extract(info, self.dir)
        self.doc_path = os.path.join(self.dir, "word", "document.xml")
        self.tree = etree.parse(self.doc_path)
        self.body = self.tree.getroot().find(q("w:body"))
        self._next_id = 1 + max([int(x) for x in self.tree.getroot().xpath("//@w:id", namespaces=NS)
                                 if x.lstrip("-").isdigit()] + [0])
        # Paragraph list frozen at load: snippets match the original text even
        # after earlier edits in the same round changed a paragraph.
        self._paras = list(self.body.iter(q("w:p")))
        self._orig_text = [self._text(p) for p in self._paras]
        self._comments = []

    # ---------- reading ----------
    @staticmethod
    def _text(p):
        out = []
        for el in p.iter(q("w:t"), q("w:delText")):
            if el.tag == q("w:t") and not any(a.tag == q("w:del") for a in el.iterancestors()):
                out.append(el.text or "")
        return "".join(out)

    def dump(self):
        for i, p in enumerate(self._paras):
            t = self._orig_text[i]
            img = " [IMG]" if p.find(".//" + q("w:drawing")) is not None else ""
            if t.strip() or img:
                print("[%d]%s %s" % (i, img, t))

    def para(self, snippet):
        hits = [i for i, t in enumerate(self._orig_text) if snippet in t]
        if len(hits) > 1:
            exact = [i for i in hits if self._orig_text[i].strip() == snippet.strip()]
            if len(exact) == 1:
                hits = exact
        if len(hits) != 1:
            raise KeyError("snippet %r matches %d paragraphs: %s" % (snippet, len(hits), hits[:10]))
        return self._paras[hits[0]]

    def text(self, snippet):
        return self._text(self.para(snippet))

    # ---------- helpers ----------
    def _id(self):
        self._next_id += 1
        return str(self._next_id)

    def _mark(self, tag):
        el = etree.Element(q(tag))
        el.set(q("w:id"), self._id())
        el.set(q("w:author"), self.author)
        el.set(q("w:date"), self.date)
        return el

    @staticmethod
    def _run(rpr, text, deleted=False):
        r = etree.Element(q("w:r"))
        if rpr is not None:
            r.append(copy.deepcopy(rpr))
        t = etree.SubElement(r, q("w:delText") if deleted else q("w:t"))
        t.text = text
        t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
        return r

    def _text_runs(self, p):
        runs = [r for r in p if r.tag == q("w:r")]
        for r in runs:
            for ch in r:
                if ch.tag not in (q("w:rPr"), q("w:t")):
                    raise ValueError("paragraph has non-text run content (%s); edit by hand" % etree.QName(ch).localname)
        return runs

    # ---------- edits ----------
    def rewrite(self, snippet, new_text):
        """Replace the whole text of a paragraph, tracking only changed words."""
        p = self.para(snippet)
        if p.find(q("w:ins")) is not None or p.find(q("w:del")) is not None:
            raise ValueError("paragraph %r already edited in this round; combine edits into one rewrite" % snippet)
        # Runs without text (formatting-only) are left untouched in place.
        runs = [r for r in self._text_runs(p) if r.find(q("w:t")) is not None]
        if not runs:
            raise ValueError("no text runs in paragraph %r" % snippet)
        chars = []  # (char, rPr) for the current text
        for r in runs:
            rpr = r.find(q("w:rPr"))
            for t in r.findall(q("w:t")):
                chars.extend((c, rpr) for c in (t.text or ""))
        old_text = "".join(c for c, _ in chars)
        if old_text == new_text:
            return
        old_tok, new_tok = _tokens(old_text), _tokens(new_text)
        starts, pos = [], 0
        for tok in old_tok:
            starts.append(pos)
            pos += len(tok)
        starts.append(pos)

        out = []

        def emit_span(a, b, deleted):
            # Split [a, b) of the old text by formatting so each piece keeps its rPr.
            i = a
            while i < b:
                rpr = chars[i][1]
                j = i
                while j < b and chars[j][1] is rpr:
                    j += 1
                run = self._run(rpr, old_text[i:j], deleted)
                if deleted:
                    wrap = self._mark("w:del")
                    wrap.append(run)
                    out.append(wrap)
                else:
                    out.append(run)
                i = j

        for op, i1, i2, j1, j2 in _merged_opcodes(old_tok, new_tok):
            a, b = starts[i1], starts[i2]
            if op == "equal":
                emit_span(a, b, False)
                continue
            if op in ("delete", "replace"):
                emit_span(a, b, True)
            if op in ("insert", "replace"):
                ref = chars[a - 1][1] if a > 0 else chars[min(a, len(chars) - 1)][1]
                wrap = self._mark("w:ins")
                wrap.append(self._run(ref, "".join(new_tok[j1:j2])))
                out.append(wrap)

        anchor = runs[0]
        idx = list(p).index(anchor)
        for r in runs:
            p.remove(r)
        for k, el in enumerate(out):
            p.insert(idx + k, el)

    def replace(self, snippet, old, new, count=1):
        """Replace `old` by `new` inside the paragraph found by `snippet`."""
        cur = self.text(snippet)
        if old not in cur:
            raise KeyError("%r not found in paragraph %r" % (old, snippet))
        self.rewrite(snippet, cur.replace(old, new, count))

    def insert_after(self, snippet, text, like=None):
        """Insert a new paragraph after the one found by `snippet`, formatted like `like`."""
        p = self.para(snippet)
        tpl = self.para(like or snippet)
        new = etree.Element(q("w:p"))
        ppr = tpl.find(q("w:pPr"))
        ppr = copy.deepcopy(ppr) if ppr is not None else etree.Element(q("w:pPr"))
        if ppr.getparent() is None:
            new.append(ppr)
        rpr_mark = ppr.find(q("w:rPr"))
        if rpr_mark is None:
            rpr_mark = etree.SubElement(ppr, q("w:rPr"))
        for old in rpr_mark.findall(q("w:ins")) + rpr_mark.findall(q("w:del")):
            rpr_mark.remove(old)
        rpr_mark.insert(0, self._mark("w:ins"))
        if ppr.getparent() is None:
            new.append(ppr)
        runs = [r for r in tpl if r.tag == q("w:r")]
        rpr = runs[-1].find(q("w:rPr")) if runs else None
        wrap = self._mark("w:ins")
        wrap.append(self._run(rpr, text))
        new.append(wrap)
        p.addnext(new)
        # Make the new paragraph addressable by its text in later calls.
        self._paras.insert(self._paras.index(p) + 1, new)
        self._orig_text.insert(self._paras.index(new), text)
        return new

    def delete_paragraph(self, snippet):
        p = self.para(snippet)
        for r in self._text_runs(p):
            wrap = self._mark("w:del")
            r.addprevious(wrap)
            wrap.append(r)
            for t in r.findall(q("w:t")):
                t.tag = q("w:delText")
        ppr = p.find(q("w:pPr"))
        if ppr is None:
            ppr = etree.Element(q("w:pPr"))
            p.insert(0, ppr)
        rpr = ppr.find(q("w:rPr"))
        if rpr is None:
            rpr = etree.SubElement(ppr, q("w:rPr"))
        rpr.insert(0, self._mark("w:del"))

    def comment(self, snippet, text, anchor=None):
        """Attach a comment to a paragraph (or to the first run containing `anchor`)."""
        p = self.para(snippet)
        cid = str(len(self._comments))
        self._comments.append((cid, text))
        runs = [el for el in p if el.tag in (q("w:r"), q("w:ins"), q("w:del"))]
        first, last = runs[0], runs[-1]
        if anchor:
            for el in runs:
                if anchor in "".join(t.text or "" for t in el.iter(q("w:t"))):
                    first = last = el
                    break
        start = etree.Element(q("w:commentRangeStart"))
        start.set(q("w:id"), cid)
        end = etree.Element(q("w:commentRangeEnd"))
        end.set(q("w:id"), cid)
        ref_run = etree.Element(q("w:r"))
        ref = etree.SubElement(ref_run, q("w:commentReference"))
        ref.set(q("w:id"), cid)
        first.addprevious(start)
        last.addnext(end)
        end.addnext(ref_run)

    # ---------- images ----------
    def _drawing_run(self, snippet):
        p = self.para(snippet)
        if p.find(".//" + q("w:drawing")) is None:
            # Allow addressing an image by its caption (the paragraph right after it).
            prev = p.getprevious()
            while prev is not None and prev.find(".//" + q("w:drawing")) is None:
                prev = prev.getprevious()
            if prev is None:
                raise KeyError("no image near %r" % snippet)
            p = prev
        for r in p.iter(q("w:r")):
            if r.find(q("w:drawing")) is not None:
                return r
        raise KeyError("no image run near %r" % snippet)

    @staticmethod
    def _set_extent(run, cx, cy):
        for ext in run.iter(q("wp:extent"), q("a:ext")):
            if ext.tag == q("a:ext") and ext.getparent().tag != q("a:xfrm"):
                continue
            ext.set("cx", str(int(cx)))
            ext.set("cy", str(int(cy)))

    def scale_image(self, snippet, factor):
        """Propose a resized image: the original is marked deleted, a resized copy inserted."""
        run = self._drawing_run(snippet)
        ext = run.find(".//" + q("wp:extent"))
        new_run = copy.deepcopy(run)
        for docpr in new_run.iter(q("wp:docPr")):
            docpr.set("id", str(int(self._id()) + 1000))
        self._set_extent(new_run, int(ext.get("cx")) * factor, int(ext.get("cy")) * factor)
        dele = self._mark("w:del")
        run.addprevious(dele)
        dele.append(run)
        ins = self._mark("w:ins")
        ins.append(new_run)
        dele.addnext(ins)

    def replace_image(self, snippet, png_path, width_cm=None):
        """Replace an image as a tracked change: old one deleted, new one inserted."""
        run = self._drawing_run(snippet)
        media_dir = os.path.join(self.dir, "word", "media")
        os.makedirs(media_dir, exist_ok=True)
        n = 1
        while os.path.exists(os.path.join(media_dir, "image_r%d.png" % n)):
            n += 1
        name = "image_r%d.png" % n
        shutil.copy(png_path, os.path.join(media_dir, name))
        rels_path = os.path.join(self.dir, "word", "_rels", "document.xml.rels")
        rels = etree.parse(rels_path)
        ids = {r.get("Id") for r in rels.getroot()}
        k = 1
        while "rIdR%d" % k in ids:
            k += 1
        rid = "rIdR%d" % k
        rel = etree.SubElement(rels.getroot(), "{%s}Relationship" % PKG_REL)
        rel.set("Id", rid)
        rel.set("Type", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image")
        rel.set("Target", "media/" + name)
        rels.write(rels_path, xml_declaration=True, encoding="UTF-8", standalone=True)
        self._ensure_png_content_type()

        new_run = copy.deepcopy(run)
        for blip in new_run.iter(q("a:blip")):
            blip.set(q("r:embed"), rid)
        for docpr in new_run.iter(q("wp:docPr")):
            docpr.set("id", str(int(self._id()) + 1000))
            docpr.set("name", name)
        w_px, h_px = _png_size(png_path)
        ext = run.find(".//" + q("wp:extent"))
        cx = width_cm * EMU_PER_CM if width_cm else int(ext.get("cx"))
        self._set_extent(new_run, cx, cx * h_px / w_px)

        dele = self._mark("w:del")
        run.addprevious(dele)
        dele.append(run)
        ins = self._mark("w:ins")
        ins.append(new_run)
        dele.addnext(ins)

    def _ensure_png_content_type(self):
        path = os.path.join(self.dir, "[Content_Types].xml")
        ct = etree.parse(path)
        if not any(el.get("Extension") == "png" for el in ct.getroot()):
            d = etree.SubElement(ct.getroot(), "{%s}Default" % CT)
            d.set("Extension", "png")
            d.set("ContentType", "image/png")
            ct.write(path, xml_declaration=True, encoding="UTF-8", standalone=True)

    # ---------- saving ----------
    def _write_comments(self):
        if not self._comments:
            return
        root = etree.Element(q("w:comments"), nsmap={"w": W})
        for cid, text in self._comments:
            c = etree.SubElement(root, q("w:comment"))
            c.set(q("w:id"), cid)
            c.set(q("w:author"), self.author)
            c.set(q("w:date"), self.date)
            c.set(q("w:initials"), self.author[:2])
            for line in text.split("\n"):
                p = etree.SubElement(c, q("w:p"))
                r = etree.SubElement(p, q("w:r"))
                t = etree.SubElement(r, q("w:t"))
                t.text = line
                t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
        path = os.path.join(self.dir, "word", "comments.xml")
        if os.path.exists(path):
            raise RuntimeError("document already has comments.xml; merging is not implemented")
        etree.ElementTree(root).write(path, xml_declaration=True, encoding="UTF-8", standalone=True)
        rels_path = os.path.join(self.dir, "word", "_rels", "document.xml.rels")
        rels = etree.parse(rels_path)
        rel = etree.SubElement(rels.getroot(), "{%s}Relationship" % PKG_REL)
        rel.set("Id", "rIdComments1")
        rel.set("Type", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments")
        rel.set("Target", "comments.xml")
        rels.write(rels_path, xml_declaration=True, encoding="UTF-8", standalone=True)
        ct_path = os.path.join(self.dir, "[Content_Types].xml")
        ct = etree.parse(ct_path)
        o = etree.SubElement(ct.getroot(), "{%s}Override" % CT)
        o.set("PartName", "/word/comments.xml")
        o.set("ContentType", "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml")
        ct.write(ct_path, xml_declaration=True, encoding="UTF-8", standalone=True)

    def save(self, out_path):
        self.tree.write(self.doc_path, xml_declaration=True, encoding="UTF-8", standalone=True)
        self._write_comments()
        self._comments = []
        if os.path.exists(out_path):
            os.remove(out_path)
        with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
            ct = os.path.join(self.dir, "[Content_Types].xml")
            z.write(ct, "[Content_Types].xml")
            for root, _, files in os.walk(self.dir):
                for f in files:
                    full = os.path.join(root, f)
                    rel = os.path.relpath(full, self.dir)
                    if rel != "[Content_Types].xml":
                        z.write(full, rel)


def _reject_all(root):
    """Undo every tracked change and comment marker in a document.xml tree."""
    for p in list(root.iter(q("w:p"))):
        mark = p.find(q("w:pPr") + "/" + q("w:rPr") + "/" + q("w:ins"))
        if mark is not None:
            p.getparent().remove(p)
    for el in list(root.iter(q("w:ins"))):
        el.getparent().remove(el)
    for el in list(root.iter(q("w:del"))):
        parent = el.getparent()
        if parent.tag == q("w:rPr"):
            parent.remove(el)
            continue
        idx = list(parent).index(el)
        for k, child in enumerate(list(el)):
            parent.insert(idx + k, child)
        parent.remove(el)
    for el in list(root.iter(q("w:delText"))):
        el.tag = q("w:t")
    for el in list(root.iter(q("w:commentRangeStart"), q("w:commentRangeEnd"))):
        el.getparent().remove(el)
    for el in list(root.iter(q("w:commentReference"))):
        run = el.getparent()
        run.getparent().remove(run)


def _normalize(root):
    """Merge adjacent plain-text runs with identical formatting; drop empty rPr and rsid noise."""
    for r in root.iter(q("w:r")):
        for attr in list(r.attrib):
            if "rsid" in attr:
                del r.attrib[attr]
    for p in root.iter(q("w:p")):
        prev = None
        for r in list(p):
            if r.tag != q("w:r") or any(ch.tag not in (q("w:rPr"), q("w:t")) for ch in r):
                prev = None
                continue
            rpr = r.find(q("w:rPr"))
            key = etree.tostring(rpr, method="c14n") if rpr is not None else b""
            if prev is not None and prev[1] == key:
                prev_t = prev[0].findall(q("w:t"))[-1]
                prev_t.text = (prev_t.text or "") + "".join(t.text or "" for t in r.findall(q("w:t")))
                prev_t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
                p.remove(r)
            else:
                for t in r.findall(q("w:t"))[1:]:
                    first = r.findall(q("w:t"))[0]
                    first.text = (first.text or "") + (t.text or "")
                    r.remove(t)
                prev = (r, key)
        for t in p.iter(q("w:t")):
            t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")


def verify_reject(original_docx, edited_docx):
    """True if rejecting every proposed change in `edited` gives back `original` exactly
    (document.xml compared after merging identically formatted runs)."""
    def body(path, reject):
        with zipfile.ZipFile(path) as z:
            root = etree.fromstring(z.read("word/document.xml"))
        if reject:
            _reject_all(root)
        _normalize(root)
        return etree.tostring(root, method="c14n")
    a, b = body(original_docx, False), body(edited_docx, True)
    if a != b:
        i = next(k for k in range(min(len(a), len(b))) if a[k] != b[k])
        print("MISMATCH near:\n  original: %r\n  rejected: %r" % (a[max(0, i - 200):i + 200], b[max(0, i - 200):i + 200]))
        return False
    return True


def changes(docx_path):
    """Print each paragraph that has tracked changes: [-deleted-] {+inserted+}."""
    d = Doc(docx_path)
    for i, p in enumerate(d._paras):
        if p.find(".//" + q("w:ins")) is None and p.find(".//" + q("w:del")) is None:
            continue
        parts = []
        for el in p.iter(q("w:t"), q("w:delText"), q("w:drawing"), q("w:commentReference")):
            inside_ins = any(a.tag == q("w:ins") for a in el.iterancestors())
            if el.tag == q("w:delText"):
                parts.append("[-%s-]" % el.text)
            elif el.tag == q("w:drawing"):
                parts.append("[-IMG-]" if any(a.tag == q("w:del") for a in el.iterancestors()) else "{+IMG+}" if inside_ins else "[IMG]")
            elif el.tag == q("w:commentReference"):
                parts.append("[comment %s]" % el.get(q("w:id")))
            else:
                parts.append("{+%s+}" % el.text if inside_ins else el.text or "")
        marked = p.find(q("w:pPr") + "/" + q("w:rPr"))
        para_mark = ""
        if marked is not None and marked.find(q("w:ins")) is not None:
            para_mark = " (new paragraph)"
        if marked is not None and marked.find(q("w:del")) is not None:
            para_mark = " (paragraph deleted)"
        print("[%d]%s %s\n" % (i, para_mark, "".join(parts)))


def _png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG: %s" % path)
    return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")


def render(docx_path, out_dir, dpi=80):
    """Convert to PDF with LibreOffice and to page JPEGs; returns the PDF path."""
    os.makedirs(out_dir, exist_ok=True)
    helper = os.environ.get("SOFFICE_HELPER")
    if helper:
        sys.path.insert(0, helper)
        from office.soffice import run_soffice
        run_soffice(["--headless", "--convert-to", "pdf", "--outdir", out_dir, docx_path])
    else:
        subprocess.run(["soffice", "--headless", "--convert-to", "pdf", "--outdir", out_dir, docx_path],
                       check=True, capture_output=True)
    pdf = os.path.join(out_dir, os.path.splitext(os.path.basename(docx_path))[0] + ".pdf")
    subprocess.run(["pdftoppm", "-jpeg", "-r", str(dpi), pdf, os.path.join(out_dir, "page")], check=True)
    return pdf


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "dump":
        Doc(sys.argv[2]).dump()
    elif cmd == "render":
        print(render(sys.argv[2], sys.argv[3]))
    elif cmd == "changes":
        changes(sys.argv[2])
    elif cmd == "verify":
        ok = verify_reject(sys.argv[2], sys.argv[3])
        print("OK: rejecting all proposals restores the original" if ok else "FAILED")
        sys.exit(0 if ok else 1)
    else:
        print(__doc__)
