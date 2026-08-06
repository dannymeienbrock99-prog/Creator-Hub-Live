const canvas = document.getElementById('giftCanvas');
const ctx = canvas.getContext('2d', { alpha: true });

const DPR_LIMIT = 2;
let dpr = 1;
let width = 0;
let height = 0;
let animationId = 0;
let active = null;

function resize() {
  dpr = Math.min(window.devicePixelRatio || 1, DPR_LIMIT);
  width = window.innerWidth;
  height = window.innerHeight;
  canvas.width = Math.max(1, Math.floor(width * dpr));
  canvas.height = Math.max(1, Math.floor(height * dpr));
  canvas.style.width = `${width}px`;
  canvas.style.height = `${height}px`;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}
window.addEventListener('resize', resize);
resize();

const clamp = (v, min, max) => Math.max(min, Math.min(max, v));
const lerp = (a, b, t) => a + (b - a) * t;
const easeOutBack = t => 1 + 2.70158 * Math.pow(t - 1, 3) + 1.70158 * Math.pow(t - 1, 2);
const easeOutCubic = t => 1 - Math.pow(1 - t, 3);
const easeInOutSine = t => -(Math.cos(Math.PI * t) - 1) / 2;

function clear() {
  ctx.clearRect(0, 0, width, height);
}

function heartPath(cx, cy, size) {
  const s = size;
  ctx.beginPath();
  ctx.moveTo(cx, cy + s * 0.42);
  ctx.bezierCurveTo(cx - s * 0.85, cy - s * 0.02, cx - s * 0.68, cy - s * 0.72, cx - s * 0.23, cy - s * 0.68);
  ctx.bezierCurveTo(cx - s * 0.05, cy - s * 0.66, cx, cy - s * 0.52, cx, cy - s * 0.42);
  ctx.bezierCurveTo(cx, cy - s * 0.52, cx + s * 0.05, cy - s * 0.66, cx + s * 0.23, cy - s * 0.68);
  ctx.bezierCurveTo(cx + s * 0.68, cy - s * 0.72, cx + s * 0.85, cy - s * 0.02, cx, cy + s * 0.42);
  ctx.closePath();
}

function drawHeartGift(t, options) {
  const cx = width * (options.x ?? 0.5);
  const cy = height * (options.y ?? 0.5);
  const base = Math.min(width, height) * (options.scale ?? 0.22);
  const intro = clamp(t / 0.55, 0, 1);
  const outro = clamp((3.3 - t) / 0.55, 0, 1);
  const alpha = Math.min(intro, outro);
  const pulse = 1 + Math.sin(t * 8.4) * 0.035;
  const size = base * easeOutBack(intro) * pulse;

  ctx.save();
  ctx.globalAlpha = alpha;
  ctx.translate(cx, cy);
  ctx.rotate(Math.sin(t * 1.8) * 0.015);
  ctx.translate(-cx, -cy);

  ctx.shadowBlur = base * 0.42;
  ctx.shadowColor = 'rgba(255, 20, 90, 0.95)';
  const fill = ctx.createRadialGradient(cx - size * 0.2, cy - size * 0.32, size * 0.05, cx, cy, size);
  fill.addColorStop(0, '#ff8ab5');
  fill.addColorStop(0.25, '#ff2d74');
  fill.addColorStop(0.65, '#b50032');
  fill.addColorStop(1, '#4d0012');
  heartPath(cx, cy, size);
  ctx.fillStyle = fill;
  ctx.fill();

  ctx.shadowBlur = base * 0.18;
  ctx.lineWidth = Math.max(3, size * 0.055);
  const stroke = ctx.createLinearGradient(cx - size, cy - size, cx + size, cy + size);
  stroke.addColorStop(0, '#ffffff');
  stroke.addColorStop(0.2, '#ff92c2');
  stroke.addColorStop(0.6, '#ff174f');
  stroke.addColorStop(1, '#ffffff');
  ctx.strokeStyle = stroke;
  ctx.stroke();

  ctx.globalCompositeOperation = 'screen';
  ctx.lineWidth = Math.max(2, size * 0.025);
  ctx.strokeStyle = 'rgba(255,255,255,0.75)';
  ctx.beginPath();
  ctx.arc(cx - size * 0.18, cy - size * 0.24, size * 0.46, Math.PI * 1.08, Math.PI * 1.64);
  ctx.stroke();

  drawHeartParticles(t, cx, cy, base, alpha, options.seed ?? 7);
  ctx.restore();
}

function drawHeartParticles(t, cx, cy, base, alpha, seed) {
  for (let i = 0; i < 42; i++) {
    const phase = (i * 0.61803398875 + seed * 0.137) % 1;
    const life = (t * 0.34 + phase) % 1;
    const angle = phase * Math.PI * 2 + Math.sin(i * 1.7) * 0.35;
    const radius = base * (0.55 + life * 1.45);
    const px = cx + Math.cos(angle) * radius;
    const py = cy + Math.sin(angle) * radius * 0.72 - life * base * 0.48;
    const a = alpha * Math.sin(Math.PI * life) * 0.9;
    const r = base * (0.012 + (i % 4) * 0.005);
    ctx.save();
    ctx.globalAlpha = a;
    ctx.shadowBlur = r * 5;
    ctx.shadowColor = '#ff336f';
    ctx.fillStyle = i % 5 === 0 ? '#ffffff' : '#ff376e';
    if (i % 3 === 0) {
      heartPath(px, py, r * 2.8);
      ctx.fill();
    } else {
      ctx.beginPath();
      ctx.arc(px, py, r, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.restore();
  }
}

function drawChestGift(t, options) {
  const cx = width * (options.x ?? 0.5);
  const cy = height * (options.y ?? 0.54);
  const base = Math.min(width, height) * (options.scale ?? 0.29);
  const intro = clamp(t / 0.65, 0, 1);
  const outro = clamp((5.2 - t) / 0.7, 0, 1);
  const alpha = Math.min(intro, outro);
  const open = easeInOutSine(clamp((t - 0.8) / 1.0, 0, 1));
  const burst = easeOutCubic(clamp((t - 1.35) / 0.7, 0, 1));
  const bob = Math.sin(t * 3.2) * base * 0.012;
  const w = base * 1.42;
  const h = base * 0.88;

  ctx.save();
  ctx.globalAlpha = alpha;
  ctx.translate(cx, cy + bob);
  ctx.scale(easeOutBack(intro), easeOutBack(intro));
  ctx.translate(-cx, -(cy + bob));

  drawGoldenAura(cx, cy, base, burst, alpha);
  drawChestBase(cx, cy, w, h, base);
  drawChestLid(cx, cy, w, h, base, open);
  drawCoins(t, cx, cy - h * 0.18, base, burst, alpha, options.seed ?? 11);
  ctx.restore();
}

function drawChestBase(cx, cy, w, h, base) {
  const x = cx - w / 2;
  const y = cy - h * 0.1;
  const bodyH = h * 0.72;
  ctx.shadowBlur = base * 0.18;
  ctx.shadowColor = 'rgba(255,166,0,0.75)';
  const wood = ctx.createLinearGradient(x, y, x + w, y + bodyH);
  wood.addColorStop(0, '#5e230d');
  wood.addColorStop(0.45, '#8a3e19');
  wood.addColorStop(1, '#3b1609');
  roundedRect(x, y, w, bodyH, base * 0.07);
  ctx.fillStyle = wood;
  ctx.fill();

  ctx.lineWidth = base * 0.075;
  ctx.strokeStyle = '#d28b16';
  ctx.stroke();
  ctx.lineWidth = base * 0.025;
  ctx.strokeStyle = '#ffe082';
  ctx.stroke();

  const lockW = base * 0.22;
  const lockH = base * 0.28;
  roundedRect(cx - lockW / 2, y + bodyH * 0.18, lockW, lockH, base * 0.035);
  ctx.fillStyle = '#f4b72e';
  ctx.fill();
  ctx.fillStyle = '#6b3505';
  ctx.beginPath();
  ctx.arc(cx, y + bodyH * 0.29, base * 0.035, 0, Math.PI * 2);
  ctx.fill();
  ctx.fillRect(cx - base * 0.018, y + bodyH * 0.29, base * 0.036, base * 0.07);
}

function drawChestLid(cx, cy, w, h, base, open) {
  const lidH = h * 0.42;
  const x = cx - w / 2;
  const closedY = cy - h * 0.42;
  ctx.save();
  ctx.translate(cx, closedY + lidH);
  ctx.rotate(-open * Math.PI * 0.46);
  ctx.translate(-cx, -(closedY + lidH));
  const grad = ctx.createLinearGradient(x, closedY, x + w, closedY + lidH);
  grad.addColorStop(0, '#4a1c0b');
  grad.addColorStop(0.55, '#7b3414');
  grad.addColorStop(1, '#2f1005');
  roundedRect(x, closedY, w, lidH, base * 0.11);
  ctx.fillStyle = grad;
  ctx.fill();
  ctx.lineWidth = base * 0.075;
  ctx.strokeStyle = '#d28b16';
  ctx.stroke();
  ctx.lineWidth = base * 0.025;
  ctx.strokeStyle = '#ffe082';
  ctx.stroke();
  ctx.restore();
}

function drawGoldenAura(cx, cy, base, burst, alpha) {
  ctx.save();
  ctx.globalCompositeOperation = 'screen';
  ctx.globalAlpha = alpha * burst;
  const g = ctx.createRadialGradient(cx, cy - base * 0.2, 0, cx, cy - base * 0.15, base * 1.3);
  g.addColorStop(0, 'rgba(255,255,210,0.95)');
  g.addColorStop(0.18, 'rgba(255,202,40,0.75)');
  g.addColorStop(0.55, 'rgba(255,150,0,0.18)');
  g.addColorStop(1, 'rgba(255,120,0,0)');
  ctx.fillStyle = g;
  ctx.beginPath();
  ctx.arc(cx, cy - base * 0.12, base * 1.35, 0, Math.PI * 2);
  ctx.fill();
  for (let i = 0; i < 18; i++) {
    const a = -Math.PI * 0.9 + (i / 17) * Math.PI * 1.8;
    const len = base * (0.65 + (i % 4) * 0.12) * burst;
    ctx.strokeStyle = `rgba(255,215,80,${0.2 + (i % 3) * 0.12})`;
    ctx.lineWidth = base * 0.018;
    ctx.beginPath();
    ctx.moveTo(cx, cy - base * 0.18);
    ctx.lineTo(cx + Math.cos(a) * len, cy - base * 0.18 + Math.sin(a) * len);
    ctx.stroke();
  }
  ctx.restore();
}

function drawCoins(t, cx, cy, base, burst, alpha, seed) {
  for (let i = 0; i < 30; i++) {
    const p = clamp((t - 1.15 - (i % 6) * 0.045) / (1.25 + (i % 5) * 0.1), 0, 1);
    if (p <= 0 || p >= 1) continue;
    const angle = ((i * 137.5 + seed * 17) % 360) * Math.PI / 180;
    const speed = base * (0.8 + (i % 7) * 0.13);
    const px = cx + Math.cos(angle) * speed * p;
    const py = cy + Math.sin(angle) * speed * p - base * 1.2 * p + base * 1.45 * p * p;
    const r = base * (0.035 + (i % 4) * 0.006);
    ctx.save();
    ctx.globalAlpha = alpha * burst * Math.sin(Math.PI * p);
    ctx.translate(px, py);
    ctx.rotate(t * 7 + i);
    ctx.scale(1, 0.55 + Math.abs(Math.sin(t * 8 + i)) * 0.45);
    ctx.shadowBlur = r * 4;
    ctx.shadowColor = '#ffb300';
    const coin = ctx.createRadialGradient(-r * 0.3, -r * 0.3, r * 0.1, 0, 0, r);
    coin.addColorStop(0, '#fff4b0');
    coin.addColorStop(0.45, '#ffd54f');
    coin.addColorStop(1, '#b26a00');
    ctx.fillStyle = coin;
    ctx.beginPath();
    ctx.ellipse(0, 0, r, r * 0.82, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.lineWidth = Math.max(1, r * 0.18);
    ctx.strokeStyle = '#fff0a0';
    ctx.stroke();
    ctx.restore();
  }
}

function roundedRect(x, y, w, h, r) {
  const rr = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + rr, y);
  ctx.arcTo(x + w, y, x + w, y + h, rr);
  ctx.arcTo(x + w, y + h, x, y + h, rr);
  ctx.arcTo(x, y + h, x, y, rr);
  ctx.arcTo(x, y, x + w, y, rr);
  ctx.closePath();
}

const durations = { heart: 3.3, chest: 5.2 };

export function playGift(name, options = {}) {
  cancelAnimationFrame(animationId);
  active = { name, options, started: performance.now() };
  frame(performance.now());
}

function frame(now) {
  clear();
  if (!active) return;
  const t = (now - active.started) / 1000;
  const duration = durations[active.name] ?? 3;
  if (active.name === 'heart') drawHeartGift(t, active.options);
  if (active.name === 'chest') drawChestGift(t, active.options);
  if (t < duration) animationId = requestAnimationFrame(frame);
  else active = null;
}

window.CreatorHubGifts = { play: playGift };

const params = new URLSearchParams(location.search);
const demo = params.get('demo');
if (demo === 'heart' || demo === 'chest') {
  setTimeout(() => playGift(demo), 300);
}
