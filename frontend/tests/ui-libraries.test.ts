import assert from 'node:assert/strict';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { motion } from 'motion/react';
import { cn } from '../src/lib/utils.ts';

test('shadcn class merging honors the tw prefix and preserves existing CSS classes', () => {
  assert.equal(cn('play-button tw:px-2 tw:bg-transparent', false, 'tw:px-4 tw:bg-primary'),
    'play-button tw:px-4 tw:bg-primary');
  assert.equal(cn('tw:hover:bg-accent', 'tw:bg-primary'), 'tw:hover:bg-accent tw:bg-primary');
});

test('Motion React entry works with the installed React and preserves button attributes', () => {
  const html = renderToStaticMarkup(createElement(motion.button,
    { disabled: true, 'aria-label': '播放', initial: false }, '播放'));
  assert.match(html, /<button/);
  assert.match(html, /disabled=""/);
  assert.match(html, /aria-label="播放"/);
});
