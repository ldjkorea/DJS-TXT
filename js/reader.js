'use strict';
/* A bounded DOM window. Progress and durable positions use paragraph/text anchors,
   never estimated whole-document pixel heights. Both reading modes share this model. */
class ReaderLayout {
    constructor(content, display, changed) {
        this.content = content;
        this.display = display;
        this.changed = changed;
        this.paragraphs = [];
        this.start = 0;
        this.end = 0;
        this.busy = false;
        this.estimate = 120;
        this.windowSize = 180;
        this.lastPosition = { pIdx: 0, offset: 0, ratio: 0, charOffset: 0 };
        this.moveToken = 0;
        content.addEventListener('scroll', () => this.scrolled(), { passive: true });
        window.addEventListener('scroll', () => this.scrolled(), { passive: true });
        this.resizeObserver = new ResizeObserver(() => {
            const signature = [content.clientWidth, innerHeight, getComputedStyle(content).fontSize].join(':');
            if (signature === this.signature)
                return;
            this.signature = signature;
            if (this.paragraphs.length && !this.busy)
                this.goTo(this.lastPosition);
        });
        this.resizeObserver.observe(content);
    }
    get page() { return document.getElementById('reader').classList.contains('mode-page'); }
    get pad() { return parseFloat(getComputedStyle(this.content).paddingTop) || 0; }
    async load(paragraphs, pos) {
        this.paragraphs = paragraphs;
        this.prefix = [0];
        for (const text of paragraphs)
            this.prefix.push(this.prefix[this.prefix.length - 1] + text.split('\n').map(line => line.trim()).filter(Boolean).join('').length);
        await this.reflow(pos || { pIdx: 0, offset: 0, ratio: 0 });
    }
    async reflow(pos) {
        this.busy = true;
        const token = this.fontToken = (this.fontToken || 0) + 1;
        const font = cfg.fontFamily === 'ridi' ? 'RIDIBatang' : cfg.fontFamily === 'sans' ? 'Pretendard Variable' : 'serif';
        try {
            await document.fonts.load(`${cfg.font}px "${font}"`);
        }
        catch (e) { }
        if (token !== this.fontToken || !this.paragraphs.length)
            return;
        this.start = -1;
        this.end = -1;
        await this.goTo(pos);
    }
    render(index, windowStart) {
        const requested = Number.isInteger(windowStart) && windowStart >= 0 && index >= windowStart && index < windowStart + this.windowSize ? windowStart : index - Math.floor(this.windowSize / 2);
        this.start = Math.max(0, Math.min(this.paragraphs.length - this.windowSize, requested));
        this.end = Math.min(this.paragraphs.length, this.start + this.windowSize);
        const frag = document.createDocumentFragment();
        if (!this.page) {
            const spacer = document.createElement('div');
            spacer.className = 'reader-spacer';
            spacer.style.height = (this.start * this.estimate) + 'px';
            frag.appendChild(spacer);
        }
        for (let i = this.start; i < this.end; i++) {
            const p = document.createElement('p');
            p.id = 'p' + i;
            const lines = this.paragraphs[i].split('\n').map(l => l.trim()).filter(Boolean);
            if (!lines.length)
                p.className = 'blank';
            else if (lines.length === 1 && isChapter(lines[0])) {
                p.className = 'chapter';
                p.textContent = lines[0];
            }
            else
                lines.forEach((line, j) => { if (j)
                    p.appendChild(document.createElement('br')); p.appendChild(document.createTextNode(line)); });
            frag.appendChild(p);
        }
        if (!this.page) {
            const spacer = document.createElement('div');
            spacer.className = 'reader-spacer';
            spacer.style.height = ((this.paragraphs.length - this.end) * this.estimate) + 'px';
            frag.appendChild(spacer);
        }
        this.display.replaceChildren(frag);
        const style = getComputedStyle(this.content), inner = this.content.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
        this.content.style.setProperty('--page-column-width', inner + 'px');
    }
    textRange(p, offset) {
        const walk = document.createTreeWalker(p, NodeFilter.SHOW_TEXT);
        let node;
        let remaining = Math.max(0, offset || 0);
        while ((node = walk.nextNode())) {
            if (remaining < node.length || !walk.currentNode.nextSibling && remaining === node.length) {
                const range = document.createRange();
                range.setStart(node, Math.min(remaining, node.length));
                range.setEnd(node, Math.min(node.length, remaining + 1));
                return range;
            }
            remaining -= node.length;
        }
        const range = document.createRange();
        range.selectNodeContents(p);
        range.collapse(false);
        return range;
    }
    capture() {
        if (this.busy || !this.paragraphs.length)
            return { ...this.lastPosition };
        // A restored character can sit inside a wrapped line. Sampling the line's
        // left edge again would replace it with another character on every reopen.
        const viewport = this.positionViewport();
        if (this.positionViewportKey === viewport)
            return { ...this.lastPosition };
        const contentRect = this.content.getBoundingClientRect(), style = getComputedStyle(this.content);
        const x = Math.max(1, contentRect.left + parseFloat(style.paddingLeft) + 2), y = this.pad + Math.max(4, cfg.font * 0.6);
        let caret = document.caretRangeFromPoint?.(x, y), p = caret?.startContainer;
        if (p?.nodeType !== Node.ELEMENT_NODE)
            p = p?.parentElement;
        p = p?.closest?.('#text-display p');
        if (!p) {
            const nodes = this.display.querySelectorAll('p');
            for (const node of nodes) {
                const rects = Array.from(node.getClientRects());
                if (rects.some(r => r.bottom > y && r.top < innerHeight && r.right > x && r.left < this.content.clientWidth + contentRect.left)) {
                    p = node;
                    break;
                }
            }
        }
        if (!p)
            return { ...this.lastPosition };
        const rect = p.getBoundingClientRect(), len = p.textContent.length;
        let charOffset = 0;
        if (caret && p.contains(caret.startContainer)) {
            const before = document.createRange();
            before.selectNodeContents(p);
            before.setEnd(caret.startContainer, caret.startOffset);
            charOffset = before.toString().length;
        }
        else if (this.page) {
            // A paragraph can span columns: find the first character in the visible column.
            let low = 0, high = Math.max(0, len - 1);
            while (low < high) {
                const mid = (low + high) >> 1, r = this.textRange(p, mid).getBoundingClientRect();
                if (r.left < x - 1)
                    low = mid + 1;
                else
                    high = mid;
            }
            charOffset = low;
        }
        else
            charOffset = Math.round(len * Math.max(0, Math.min(1, (y - rect.top) / Math.max(1, rect.height))));
        const pIdx = Number(p.id.slice(1)), ratio = this.page ? (len ? charOffset / len : 0) : Math.max(0, Math.min(1, (this.pad - rect.top) / Math.max(1, rect.height)));
        const position = { pIdx, charOffset, windowStart: this.start, offset: Math.max(0, Math.round(this.pad - rect.top)), ratio };
        position.pct = this.percent(position);
        this.lastPosition = position;
        this.positionViewportKey = viewport;
        return { ...position };
    }
    positionViewport() {
        return [this.page, scrollY, this.content.scrollLeft, this.content.clientWidth,
            innerHeight, this.pad, cfg.font, cfg.fontFamily, cfg.line, cfg.pad, cfg.gap].join(':');
    }
    percent(pos = this.lastPosition) {
        if (!this.paragraphs.length)
            return 0;
        if (pos.pIdx === 0 && (this.page ? this.content.scrollLeft === 0 : scrollY === 0))
            return 0;
        if (this.end === this.paragraphs.length) {
            if (this.page && this.content.scrollWidth > this.content.clientWidth && this.content.scrollLeft >= this.content.scrollWidth - this.content.clientWidth - 1)
                return 100;
            if (!this.page && document.documentElement.scrollHeight > innerHeight && scrollY >= document.documentElement.scrollHeight - innerHeight - 1)
                return 100;
        }
        return Math.max(0, Math.min(100, ((this.prefix?.[pos.pIdx] || 0) + (pos.charOffset || 0)) / Math.max(1, this.prefix?.[this.paragraphs.length] || 1) * 100));
    }
    goTo(pos) {
        if (!this.paragraphs.length)
            return;
        if (typeof pos === 'number')
            return this.seekLegacy(pos);
        const index = Math.max(0, Math.min(this.paragraphs.length - 1, Number.isInteger(pos?.pIdx) ? pos.pIdx : 0));
        this.busy = true;
        if (index < this.start || index >= this.end || !document.getElementById('p' + index) || this.renderedPage !== this.page) {
            this.render(index, pos.windowStart);
            this.renderedPage = this.page;
        }
        const p = document.getElementById('p' + index);
        if (!p) {
            this.busy = false;
            return;
        }
        const rect = p.getBoundingClientRect(), charOffset = Number.isFinite(pos.charOffset) ? Math.max(0, Math.min(p.textContent.length, pos.charOffset)) : null;
        if (this.page) {
            const offset = charOffset ?? Math.round((pos.ratio || 0) * p.textContent.length), rangeRect = this.textRange(p, offset).getBoundingClientRect();
            const left = rangeRect.left - this.content.getBoundingClientRect().left + this.content.scrollLeft - parseFloat(getComputedStyle(this.content).paddingLeft);
            this.content.scrollLeft = Math.max(0, Math.floor((left + 2) / this.content.clientWidth) * this.content.clientWidth);
            window.scrollTo(0, 0);
        }
        else {
            const top = rect.top + scrollY;
            const textRect = charOffset !== null ? this.textRange(p, charOffset).getBoundingClientRect() : null;
            const offset = textRect ? textRect.top + textRect.height / 2 - rect.top - Math.max(4, cfg.font * 0.6) : (Number.isFinite(pos.ratio) ? pos.ratio * rect.height : Number(pos.offset) || 0);
            window.scrollTo(0, Math.max(0, top + offset - this.pad));
        }
        this.lastPosition = { pIdx: index, charOffset: charOffset ?? Math.round((pos.ratio || 0) * p.textContent.length), windowStart: this.start, offset: pos.offset || 0, ratio: pos.ratio || 0 };
        this.lastPosition.pct = this.percent();
        this.positionViewportKey = charOffset !== null ? this.positionViewport() : null;
        const token = ++this.moveToken;
        requestAnimationFrame(() => requestAnimationFrame(() => { if (token !== this.moveToken)
            return; this.busy = false; this.capture(); this.changed?.(); }));
    }
    async seekLegacy(pixel) {
        // Old numeric positions are scroll pixels. Measure their prefix in bounded
        // batches once instead of guessing an index or allocating the whole book DOM.
        const token = ++this.moveToken, reader = document.getElementById('reader'), wasPage = this.page;
        this.busy = true;
        reader.classList.remove('mode-page');
        let cursor = this.pad, previousMargin = 0, found = { pIdx: this.paragraphs.length - 1, ratio: 1 };
        const target = Math.max(0, pixel) + this.pad;
        outer: for (let start = 0; start < this.paragraphs.length; start += this.windowSize) {
            this.render(start, start);
            for (const p of this.display.querySelectorAll('p')) {
                if (Number(p.id.slice(1)) < start)
                    continue;
                const style = getComputedStyle(p), height = p.getBoundingClientRect().height;
                const top = cursor + Math.max(previousMargin, parseFloat(style.marginTop) || 0), bottom = top + height;
                if (bottom >= target) {
                    found = { pIdx: Number(p.id.slice(1)), offset: Math.max(0, target - top), ratio: Math.max(0, Math.min(1, (target - top) / Math.max(1, height))) };
                    break outer;
                }
                cursor = bottom;
                previousMargin = parseFloat(style.marginBottom) || 0;
            }
            await new Promise(resolve => requestAnimationFrame(resolve));
            if (token !== this.moveToken)
                return;
        }
        reader.classList.toggle('mode-page', wasPage);
        this.start = -1;
        this.end = -1;
        this.goTo(found);
    }
    scrolled() {
        if (this.busy || !this.paragraphs.length || !curBook)
            return;
        const pos = this.capture();
        if ((pos.pIdx < this.start + 15 && this.start > 0) || (pos.pIdx > this.end - 15 && this.end < this.paragraphs.length)) {
            this.start = -1;
            this.end = -1;
            this.goTo(pos);
        }
        else if (!this.page) {
            const first = document.getElementById('p' + this.start)?.getBoundingClientRect(), last = document.getElementById('p' + (this.end - 1))?.getBoundingClientRect();
            if (first && last && (first.top > innerHeight || last.bottom < 0))
                this.goTo({ pIdx: Math.max(0, Math.min(this.paragraphs.length - 1, Math.floor(scrollY / this.estimate))), ratio: 0 });
        }
        this.changed?.();
    }
    turn(delta) {
        if (this.page) {
            const max = this.content.scrollWidth - this.content.clientWidth, next = this.content.scrollLeft + delta * this.content.clientWidth;
            if (next > max + 2 && this.end < this.paragraphs.length)
                this.goTo({ pIdx: this.end, charOffset: 0 });
            else if (next < 0 && this.start > 0)
                this.goTo({ pIdx: Math.max(0, this.start - 1), charOffset: 0 });
            else
                this.content.scrollTo({ left: Math.max(0, Math.min(max, next)), behavior: 'auto' });
        }
        else
            window.scrollBy({ top: innerHeight * 0.8 * delta, behavior: 'auto' });
    }
    progress(pct) { this.goTo({ pIdx: Math.round((this.paragraphs.length - 1) * Math.max(0, Math.min(100, pct)) / 100), charOffset: 0 }); }
    stop() { this.fontToken = (this.fontToken || 0) + 1; this.moveToken++; this.paragraphs = []; this.display.replaceChildren(); this.start = 0; this.end = 0; this.positionViewportKey = null; this.busy = false; }
}
