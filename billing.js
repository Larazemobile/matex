(function (global) {
  "use strict";

  var PRODUCT_ID = "full_access_lifetime";
  var state = {
    nativeAndroid: false,
    owned: true,
    available: false,
    loading: false,
    price: "3,99 €"
  };
  var listenerAttached = false;

  function plugin() {
    return global.Capacitor && global.Capacitor.Plugins
      ? global.Capacitor.Plugins.OrbitakidxBilling
      : null;
  }

  function isAndroidApp() {
    if (!global.Capacitor || typeof global.Capacitor.getPlatform !== "function") return false;
    return global.Capacitor.getPlatform() === "android";
  }

  function saveOwned(owned) {
    try { localStorage.setItem("orbitakidx_full_access_v1", owned ? "1" : "0"); } catch (e) {}
  }

  function cachedOwned() {
    try { return localStorage.getItem("orbitakidx_full_access_v1") === "1"; } catch (e) { return false; }
  }

  function init() {
    state.nativeAndroid = isAndroidApp();
    if (!state.nativeAndroid) {
      state.owned = true;
      return Promise.resolve(state);
    }

    state.owned = cachedOwned();
    var billing = plugin();
    if (!billing) {
      // A build without the native bridge must never lock an existing user out.
      state.owned = true;
      return Promise.resolve(state);
    }

    listenForExternalRedemptions();
    state.loading = true;
    return billing.getStatus().then(function (result) {
      state.loading = false;
      state.owned = !!result.owned;
      state.available = !!result.available;
      if (result.price) state.price = result.price;
      saveOwned(state.owned);
      return state;
    }).catch(function () {
      state.loading = false;
      // Preserve a previously verified entitlement while offline.
      state.owned = cachedOwned();
      return state;
    });
  }

  function hasFullAccess() {
    return !state.nativeAndroid || state.owned;
  }

  function showPaywall() {
    if (typeof global.showModal !== "function") return;
    var price = state.price || "3,99 €";
    global.showModal(
      '<div class="wintro">'
      + '<div style="font-size:46px;line-height:1">🔓</div>'
      + '<h2>Desbloquea todo</h2>'
      + '<p>Accede a todos los niveles, retos y exámenes para siempre.</p>'
      + '<div class="result-actions">'
      + '<div class="btn gold" onclick="OrbitakidxAccess.buy()">Desbloquear por ' + price + '</div>'
      + '<div class="btn ghost" onclick="OrbitakidxAccess.restore()">Restaurar compra</div>'
      + '<div class="btn ghost" onclick="closeModal()">Seguir con la demo</div>'
      + '</div>'
      + '<div style="margin-top:18px;padding-top:16px;border-top:2px solid rgba(35,38,61,.12)">'
      + '<p style="font-weight:900;margin:0 0 8px">¿Tienes un código?</p>'
      + '<input class="name-input" id="accessCode" maxlength="80" autocapitalize="characters" autocomplete="off" placeholder="Introduce tu código">'
      + '<div class="btn purple" style="margin-top:9px" onclick="OrbitakidxAccess.redeemCode()">Canjear código</div>'
      + '</div>'
      + '<p class="sub" style="margin-top:14px">Pago único en Google Play. Sin anuncios ni suscripción. La compra debe realizarla una persona adulta.</p>'
      + '</div>'
    );
  }

  function requireLevel(level, isExam) {
    if (hasFullAccess()) return true;
    if (!isExam && String(level) === "facil") return true;
    showPaywall();
    return false;
  }

  function requireMatex(topic, key, isExam) {
    if (hasFullAccess()) return true;
    if (!isExam && topic === "mult" && key !== "mix" && Number(key) <= 3) return true;
    if (!isExam && (topic === "suma" || topic === "resta") && key === "facil") return true;
    showPaywall();
    return false;
  }

  function requireShop() {
    if (hasFullAccess()) return true;
    var completed = 0;
    try { completed = parseInt(localStorage.getItem("eurix_shop_demo_sales") || "0", 10); } catch (e) {}
    if (completed < 2) return true;
    showPaywall();
    return false;
  }

  function recordShopSale() {
    if (hasFullAccess()) return;
    try {
      var completed = parseInt(localStorage.getItem("eurix_shop_demo_sales") || "0", 10);
      localStorage.setItem("eurix_shop_demo_sales", String(completed + 1));
    } catch (e) {}
  }

  function buy() {
    var billing = plugin();
    if (!billing) return;
    billing.purchase().then(function (result) {
      if (result && result.owned) {
        state.owned = true;
        saveOwned(true);
        if (typeof global.closeModal === "function") global.closeModal();
        if (typeof global.goHome === "function") global.goHome();
        if (typeof global.showModal === "function") {
          global.showModal('<h2>¡Todo desbloqueado! 🎉</h2><p>Ya tienes acceso completo para siempre.</p><div class="btn green" onclick="closeModal()">Continuar</div>');
        }
      }
    }).catch(function () {
      if (typeof global.showModal === "function") {
        global.showModal('<h2>No se pudo completar</h2><p>Comprueba tu conexión con Google Play y vuelve a intentarlo.</p><div class="result-actions"><div class="btn gold" onclick="OrbitakidxAccess.buy()">Reintentar</div><div class="btn ghost" onclick="closeModal()">Cerrar</div></div>');
      }
    });
  }

  function applyEntitlement(result) {
    if (!result || !result.owned) return false;
    state.owned = true;
    saveOwned(true);
    if (typeof global.closeModal === "function") global.closeModal();
    if (typeof global.goHome === "function") global.goHome();
    if (typeof global.showModal === "function") {
      global.showModal('<h2>¡Todo desbloqueado! 🎉</h2><p>Ya tienes acceso completo para siempre.</p><div class="btn green" onclick="closeModal()">Continuar</div>');
    }
    return true;
  }

  function restore() {
    var billing = plugin();
    if (!billing) return;
    billing.restore().then(function (result) {
      state.owned = !!(result && result.owned);
      saveOwned(state.owned);
      if (state.owned) {
        if (typeof global.closeModal === "function") global.closeModal();
        if (typeof global.goHome === "function") global.goHome();
        global.showModal('<h2>Compra restaurada ✓</h2><p>Ya tienes acceso completo.</p><div class="btn green" onclick="closeModal()">Continuar</div>');
      } else {
        global.showModal('<h2>No encontramos una compra</h2><p>Usa la misma cuenta de Google con la que se realizó el desbloqueo.</p><div class="btn ghost" onclick="closeModal()">Cerrar</div>');
      }
    }).catch(function () {
      global.showModal('<h2>No se pudo restaurar</h2><p>Comprueba tu conexión con Google Play y vuelve a intentarlo.</p><div class="btn ghost" onclick="closeModal()">Cerrar</div>');
    });
  }

  function redeemCode() {
    var input = document.getElementById("accessCode");
    var code = input ? input.value.trim() : "";
    if (!code) {
      if (input) input.focus();
      return;
    }
    var billing = plugin();
    if (!billing || typeof billing.redeemCode !== "function") return;
    billing.redeemCode({ code: code }).then(function () {
      if (typeof global.closeModal === "function") global.closeModal();
    }).catch(function () {
      global.showModal('<h2>Código no válido</h2><p>Comprueba que esté completo y vuelve a intentarlo.</p><div class="btn ghost" onclick="OrbitakidxAccess.showPaywall()">Volver</div>');
    });
  }

  function listenForExternalRedemptions() {
    var billing = plugin();
    if (listenerAttached || !billing || typeof billing.addListener !== "function") return;
    listenerAttached = true;
    try {
      var registration = billing.addListener("entitlementChanged", function (result) {
        applyEntitlement(result);
      });
      if (registration && typeof registration.catch === "function") {
        registration.catch(function () { listenerAttached = false; });
      }
    } catch (error) {
      listenerAttached = false;
    }
  }

  global.OrbitakidxAccess = {
    productId: PRODUCT_ID,
    init: init,
    hasFullAccess: hasFullAccess,
    requireLevel: requireLevel,
    requireMatex: requireMatex,
    requireShop: requireShop,
    recordShopSale: recordShopSale,
    showPaywall: showPaywall,
    buy: buy,
    restore: restore,
    redeemCode: redeemCode
  };
})(window);
