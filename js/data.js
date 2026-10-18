'use strict';
/* Shared validation for persisted settings and portable backups. No storage keys are renamed. */
const ReaderData = (() => {
    const defaults = { font: 19, line: 1.9, pad: 22, gap: 0.7, fontFamily: 'ridi', weight: 400, theme: 'graphite', brightness: 0, asSpeed: 3, wrap: 'keep-all', indent: 0, viewMode: 'scroll', volumeKey: true, volumeKeyInvert: false, keepScreen: true };
    const object = value => value && typeof value === 'object' && !Array.isArray(value);
    const safeId = id => typeof id === 'string' && id.length <= 512 && !/[\u0000-\u001f<>"'`]/.test(id) && !['__proto__', 'constructor', 'prototype'].includes(id);
    function config(input) {
        const c = { ...defaults }, v = object(input) ? input : {};
        const limits = { font: [8, 40], line: [1.2, 3.5], pad: [0, 80], gap: [0, 2.5], brightness: [0, 80], asSpeed: [1, 10] };
        for (const [key, [min, max]] of Object.entries(limits))
            if (typeof v[key] === 'number' && Number.isFinite(v[key]))
                c[key] = Math.max(min, Math.min(max, v[key]));
        const options = { fontFamily: ['ridi', 'serif', 'sans'], weight: [400, 700], theme: ['graphite', 'sepia', 'light', 'dark', 'night', 'green'], wrap: ['keep-all', 'break-all'], indent: [0, 1], viewMode: ['scroll', 'page'] };
        for (const [key, values] of Object.entries(options))
            if (values.includes(v[key]))
                c[key] = v[key];
        for (const key of ['volumeKey', 'volumeKeyInvert', 'keepScreen'])
            if (typeof v[key] === 'boolean')
                c[key] = v[key];
        return c;
    }
    function position(pos) {
        if (typeof pos === 'number' && Number.isFinite(pos) && pos >= 0)
            return pos;
        if (!object(pos) || !Number.isInteger(pos.pIdx) || pos.pIdx < 0)
            return { pIdx: 0, offset: 0, ratio: 0 };
        const out = { pIdx: pos.pIdx };
        for (const key of ['offset', 'ratio', 'progress', 'charOffset', 'windowStart'])
            if (Number.isFinite(pos[key]) && pos[key] >= 0)
                out[key] = pos[key];
        if (Number.isFinite(out.ratio))
            out.ratio = Math.min(1, out.ratio);
        if (Number.isFinite(out.progress))
            out.progress = Math.min(100, out.progress);
        if (typeof pos.chapter === 'string')
            out.chapter = pos.chapter;
        return out;
    }
    function book(input) {
        if (!object(input))
            throw new Error('책 정보 형식이 올바르지 않습니다');
        const out = {};
        for (const key of ['name', 'fileName', 'fingerprint', 'chapter'])
            if (typeof input[key] === 'string')
                out[key] = input[key];
        if (!out.name && !out.fileName)
            throw new Error('작품명이 없습니다');
        for (const key of ['size', 'importedAt', 'lastRead', 'progress'])
            if (Number.isFinite(input[key]) && input[key] >= 0)
                out[key] = input[key];
        out.pos = position(input.pos);
        out.bookmarks = (Array.isArray(input.bookmarks) ? input.bookmarks : []).filter(object).map(b => ({ pos: position(b.pos), pct: Number.isFinite(b.pct) ? b.pct : 0, text: typeof b.text === 'string' ? b.text : '', time: Number.isFinite(b.time) ? b.time : 0 }));
        return out;
    }
    function backup(input) {
        if (!object(input) || !object(input.books) || ![2, 3].includes(input.version))
            throw new Error('지원하지 않는 백업 형식입니다');
        const books = Object.create(null), contents = Object.create(null);
        for (const [id, value] of Object.entries(input.books)) {
            if (!safeId(id))
                throw new Error('안전하지 않은 책 식별자입니다');
            books[id] = book(value);
            if (input.version === 3) {
                if (!object(input.contents) || typeof input.contents[id] !== 'string')
                    throw new Error('백업에 책 본문이 없습니다');
                contents[id] = input.contents[id];
            }
        }
        return { version: input.version, books, contents, cfg: input.cfg ? config(input.cfg) : null, lastBook: safeId(input.lastBook) ? input.lastBook : null };
    }
    function mergeBook(current, incoming) {
        if (!current)
            return incoming;
        const latest = (Number(current.lastRead) || 0) >= (Number(incoming.lastRead) || 0) ? current : incoming;
        const bookmarks = [], seen = new Set();
        for (const b of [...(current.bookmarks || []), ...(incoming.bookmarks || [])]) {
            const p=b.pos;
            const anchor=typeof p==='number'?p:[p?.pIdx,p?.charOffset,p?.windowStart,p?.offset,p?.ratio,p?.progress,p?.chapter];
            const key = JSON.stringify([b.time, anchor, b.text]);
            if (!seen.has(key)) {
                seen.add(key);
                bookmarks.push(b);
            }
        }
        return { ...incoming, ...current, pos: latest.pos, progress: latest.progress, chapter: latest.chapter, lastRead: latest.lastRead, bookmarks };
    }
    return { config, position, book, backup, mergeBook, safeId };
})();
if (typeof module !== 'undefined')
    module.exports = ReaderData;
