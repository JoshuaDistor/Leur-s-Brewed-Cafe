/* Shared helpers for every page */
const API = {
  async call(method, url, data) {
    const opt = { method, credentials: 'same-origin', headers: {} };
    if (data) { opt.headers['Content-Type'] = 'application/x-www-form-urlencoded'; opt.body = new URLSearchParams(data).toString(); }
    const res = await fetch(url, opt);
    let json = {};
    try { json = await res.json(); } catch (e) { /* empty body */ }
    if (!res.ok) throw Object.assign(new Error(json.error || 'Something went wrong.'), { status: res.status });
    return json;
  },
  get: (u) => API.call('GET', u),
  post: (u, d) => API.call('POST', u, d || {}),
  del: (u) => API.call('DELETE', u)
};

const peso = (n) => '₱' + Number(n).toLocaleString('en-PH', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const img = (name) => (String(name).startsWith('uploads/') ? name : 'img/' + encodeURIComponent(name));

function toast(msg, isErr) {
  let w = document.querySelector('.toast-wrap');
  if (!w) { w = document.createElement('div'); w.className = 'toast-wrap'; document.body.appendChild(w); }
  const t = document.createElement('div');
  t.className = 'toast-msg' + (isErr ? ' err' : '');
  t.textContent = msg; w.appendChild(t);
  setTimeout(() => t.remove(), 3200);
}

/* Redirects to login when signed out, or to the right home when the role doesn't match. */
async function requireUser(role) {
  try {
    const u = await API.get('/api/me');
    if (role && u.role !== role) { location.href = u.role === 'admin' ? 'admin.html' : 'menu.html'; return null; }
    return u;
  } catch (e) { location.href = 'login.html'; return null; }
}

async function logout() { try { await API.post('/api/logout'); } finally { location.href = 'login.html'; } }

/* Customer top bar: logo + title, MENU / ORDERS tabs, profile menu */
function renderCustomerNav(title, active, user) {
  const bar = document.getElementById('topbar');
  bar.classList.add('topbar-nav');
  bar.innerHTML = `
    <a class="brand" href="menu.html"><img src="img/logo.jpg" alt="Leur's cafe logo"><h1>${esc(title)}</h1></a>
    <nav class="navlinks">
      <a href="menu.html" class="${active === 'menu' ? 'active' : ''}">MENU</a>
      <a href="order.html" class="${active === 'orders' ? 'active' : ''}">ORDERS</a>
    </nav>
    <div class="dropdown ms-auto">
      <button class="profile-btn" data-bs-toggle="dropdown" aria-expanded="false">
        <span class="avatar">${esc(user.username.charAt(0).toUpperCase())}</span><br>Profile
      </button>
      <ul class="dropdown-menu dropdown-menu-end">
        <li><span class="dropdown-item-text small text-muted">${esc(user.email)}</span></li>
        <li><a class="dropdown-item" href="account.html">Account settings</a></li>
        <li><hr class="dropdown-divider"></li>
        <li><button class="dropdown-item" onclick="logout()">Log out</button></li>
      </ul>
    </div>`;
}

/* ---------- show / hide toggle on every password field ---------- */
const EYE = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>';
const EYE_OFF = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M17.9 17.9A10.1 10.1 0 0 1 12 19c-6.4 0-10-7-10-7a18.5 18.5 0 0 1 5.1-5.9M9.9 4.2A9.7 9.7 0 0 1 12 4c6.4 0 10 8 10 8a18.6 18.6 0 0 1-2.2 3.2M1 1l22 22"/><path d="M14.1 14.1a3 3 0 1 1-4.2-4.2"/></svg>';
function addPasswordToggles() {
  document.querySelectorAll('input[type=password]').forEach((inp) => {
    if (inp.parentElement.classList.contains('pw-wrap')) return;
    const wrap = document.createElement('div'); wrap.className = 'pw-wrap';
    inp.parentNode.insertBefore(wrap, inp); wrap.appendChild(inp);
    const btn = document.createElement('button');
    btn.type = 'button'; btn.className = 'pw-toggle'; btn.setAttribute('aria-label', 'Show password'); btn.innerHTML = EYE;
    btn.addEventListener('click', () => {
      const show = inp.type === 'password';
      inp.type = show ? 'text' : 'password';
      btn.innerHTML = show ? EYE_OFF : EYE;
      btn.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
    });
    wrap.appendChild(btn);
  });
}
document.addEventListener('DOMContentLoaded', addPasswordToggles);
