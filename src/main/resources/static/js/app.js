(function () {
  'use strict';

  /* ---------- 로그인 전: 좁은 화면 햄버거 드롭다운 ---------- */
  var toggle = document.querySelector('.menu-toggle:not(.menu-toggle--mega)');
  var nav = document.getElementById('site-nav');
  if (toggle && nav) {
    var setOpen = function (open) {
      toggle.setAttribute('aria-expanded', String(open));
      nav.classList.toggle('is-open', open);
    };

    toggle.addEventListener('click', function () {
      setOpen(toggle.getAttribute('aria-expanded') !== 'true');
    });

    nav.addEventListener('click', function (e) {
      if (e.target.closest('a')) setOpen(false);
    });

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && toggle.getAttribute('aria-expanded') === 'true') {
        setOpen(false);
        toggle.focus();
      }
    });
  }

  /* ---------- 로그인 후: 전체 메뉴 ---------- */
  var megaToggle = document.querySelector('.menu-toggle--mega');
  var mega = document.getElementById('mega-menu');
  if (megaToggle && mega) {
    var setMega = function (open, returnFocus) {
      megaToggle.setAttribute('aria-expanded', String(open));
      mega.hidden = !open;
      if (open) {
        var first = mega.querySelector('a');
        if (first) first.focus({ preventScroll: true });
      } else if (returnFocus) {
        megaToggle.focus();
      }
    };

    megaToggle.addEventListener('click', function () {
      setMega(megaToggle.getAttribute('aria-expanded') !== 'true', false);
    });

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !mega.hidden) setMega(false, true);
    });

    // 메뉴 밖을 누르면 닫는다
    document.addEventListener('click', function (e) {
      if (!mega.hidden && !e.target.closest('.mega') && !e.target.closest('.menu-toggle--mega')) setMega(false, false);
    });

    // 뒤로 가기(bfcache)로 돌아왔을 때 열린 채로 남지 않게 한다
    window.addEventListener('pageshow', function () {
      if (!mega.hidden) setMega(false, false);
    });
  }

  /* ---------- 서버 응답 대기 로딩 (data-loading 폼) ---------- */
  var loading = document.getElementById('loading');
  var loadingText = document.getElementById('loading-text');
  var loadingForms = document.querySelectorAll('form[data-loading]');

  function hideLoading() {
    if (!loading) return;
    loading.hidden = true;
    loadingForms.forEach(function (f) {
      var b = f.querySelector('button[type="submit"]');
      if (b) b.disabled = false;
    });
  }

  if (loading) {
    loadingForms.forEach(function (form) {
      form.addEventListener('submit', function () {
        if (!form.checkValidity()) return;
        loadingText.textContent = form.getAttribute('data-loading');
        loading.hidden = false;
        // 제출이 시작된 뒤에 비활성화해야 폼 값이 함께 전송된다
        setTimeout(function () {
          var b = form.querySelector('button[type="submit"]');
          if (b) b.disabled = true;
        }, 0);
      });
    });
    // 뒤로 가기(bfcache)로 돌아왔을 때 로딩이 남지 않게 한다
    window.addEventListener('pageshow', hideLoading);
  }

  /* ---------- 비밀번호 조건·일치 표시 ---------- */
  var pwForm = document.querySelector('form[data-password-form]');
  if (pwForm) {
    var pw = pwForm.querySelector('input[name="password"]');
    var confirm = pwForm.querySelector('input[name="passwordConfirm"]');
    var hint = pwForm.querySelector('[data-match-hint]');
    var rules = {
      length: function (v) { return v.length >= 8; },
      letter: function (v) { return /[A-Za-z]/.test(v); },
      digit: function (v) { return /\d/.test(v); },
      special: function (v) { return /[!-\/:-@\[-`{-~]/.test(v); }
    };
    var idleHint = hint.textContent;

    var update = function () {
      Object.keys(rules).forEach(function (name) {
        var li = pwForm.querySelector('[data-rule="' + name + '"]');
        if (li) li.classList.toggle('is-met', rules[name](pw.value));
      });
      hint.classList.remove('is-ok', 'is-bad');
      if (!confirm.value) {
        hint.textContent = idleHint;
      } else if (confirm.value === pw.value) {
        hint.textContent = '비밀번호가 일치합니다.';
        hint.classList.add('is-ok');
      } else {
        hint.textContent = '비밀번호가 일치하지 않습니다.';
        hint.classList.add('is-bad');
      }
    };
    pw.addEventListener('input', update);
    confirm.addEventListener('input', update);
  }

  /* ---------- 로그인 유지 타이머 ----------
     서버가 알려 준 남은 시간(초)부터 1초씩 센다. 다른 탭의 활동을 반영하려고 30초마다 서버에 남은 시간을 묻는다
     (묻는 것은 활동으로 치지 않아 세션이 늘어나지 않는다). 0 이 되면 로그인 화면으로 보낸다. */
  var session = document.querySelector('.session[data-session-remaining]');
  if (session) {
    var timeEl = session.querySelector('[data-session-time]');
    var announce = session.querySelector('[data-session-announce]');
    var button = session.querySelector('.session__extend');
    var remaining = parseInt(session.getAttribute('data-session-remaining'), 10) || 0;
    var deadline = Date.now() + remaining * 1000;
    var announced = 0;

    var render = function () {
      var left = Math.max(0, Math.round((deadline - Date.now()) / 1000));
      var m = Math.floor(left / 60);
      var sec = left % 60;
      timeEl.textContent = (m < 10 ? '0' : '') + m + ':' + (sec < 10 ? '0' : '') + sec;
      session.classList.toggle('is-warn', left <= 300 && left > 60);
      session.classList.toggle('is-urgent', left <= 60);
      // 화면 낭독기에는 5분, 1분 남았을 때만 한 번씩 알린다
      if (left <= 60 && announced < 2) { announced = 2; announce.textContent = '1분 뒤 자동 로그아웃됩니다. 연장 버튼을 눌러 주세요.'; }
      else if (left <= 300 && left > 60 && announced < 1) { announced = 1; announce.textContent = '5분 뒤 자동 로그아웃됩니다.'; }
      if (left === 0) expire();
    };
    /* 0 이 되면 서버에 한 번 더 묻고(묻는 것은 연장이 아니다) 정말 끝났을 때만 로그인 화면으로 간다.
       시계가 어긋나 서버에 아직 시간이 남아 있으면 그만큼 더 센다. 이동하는 요청 자체가 세션을 늘리지 않도록 1초 여유를 둔다 */
    var expiring = false;
    var expire = function () {
      if (expiring) return;
      expiring = true;
      var go = function () { window.location.href = session.getAttribute('data-login-url'); };
      fetch(session.getAttribute('data-status-url'), { credentials: 'same-origin', headers: { Accept: 'application/json' } })
        .then(function (res) { return res.ok ? res.json() : null; })
        .then(function (data) {
          expiring = false;
          if (data && data.remainingSeconds > 0) setRemaining(data.remainingSeconds);
          else window.setTimeout(go, 1100);
        })
        .catch(function () { expiring = false; window.setTimeout(go, 1100); });
    };
    var setRemaining = function (seconds) {
      deadline = Date.now() + seconds * 1000;
      if (seconds > 300) announced = 0;
      render();
    };

    render();
    window.setInterval(render, 1000);

    var poll = function () {
      fetch(session.getAttribute('data-status-url'), { credentials: 'same-origin', headers: { Accept: 'application/json' } })
        .then(function (res) { if (res.status === 401 || res.redirected) { setRemaining(0); return null; } return res.ok ? res.json() : null; })
        .then(function (data) { if (data && typeof data.remainingSeconds === 'number') setRemaining(data.remainingSeconds); })
        .catch(function () { /* 네트워크 오류면 현재 값으로 계속 센다 */ });
    };
    window.setInterval(poll, 30000);
    // 탭으로 돌아왔을 때(절전·백그라운드 후) 바로 맞춘다
    document.addEventListener('visibilitychange', function () { if (!document.hidden) poll(); });

    session.addEventListener('submit', function (e) {
      e.preventDefault();
      button.disabled = true;
      fetch(session.getAttribute('action'), { method: 'POST', credentials: 'same-origin', body: new FormData(session), headers: { Accept: 'application/json' } })
        .then(function (res) { return res.ok ? res.json() : null; })
        .then(function (data) {
          if (data && typeof data.remainingSeconds === 'number') { setRemaining(data.remainingSeconds); announce.textContent = '로그인 유지 시간을 연장했습니다.'; announced = 0; }
          else window.location.href = session.getAttribute('data-login-url');
        })
        .catch(function () { /* 실패하면 시간은 그대로 둔다 */ })
        .then(function () { button.disabled = false; });
    });
  }
  // 이체 금액: 입력하는 동안 3자리마다 쉼표를 찍어 보여 주고, 보낼 때는 쉼표를 뺀 숫자만 보낸다.
  var money = document.querySelector('input.field__money');
  if (money) {
    var group = function (digits) { return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ','); };
    var format = function () {
      var before = money.value.slice(0, money.selectionStart || 0).replace(/\D/g, '').length;   // 커서 앞 숫자 개수
      var digits = money.value.replace(/\D/g, '').replace(/^0+(?=\d)/, '');
      var text = group(digits);
      if (text === money.value) return;
      money.value = text;
      var pos = 0, seen = 0;
      while (pos < text.length && seen < before) { if (text.charAt(pos) !== ',') seen++; pos++; }
      money.setSelectionRange(pos, pos);
    };
    money.addEventListener('input', format);
    money.value = group(money.value.replace(/\D/g, ''));
    if (money.form) money.form.addEventListener('submit', function () { money.value = money.value.replace(/\D/g, ''); });
  }
})();
