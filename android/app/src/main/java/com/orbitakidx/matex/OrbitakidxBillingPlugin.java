package com.orbitakidx.matex;

import androidx.annotation.NonNull;

import android.content.Intent;
import android.net.Uri;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@CapacitorPlugin(name = "OrbitakidxBilling")
public class OrbitakidxBillingPlugin extends Plugin implements PurchasesUpdatedListener {
    private static final String PRODUCT_ID = "full_access_lifetime";

    private BillingClient billingClient;
    private PluginCall pendingPurchaseCall;

    private interface Completion {
        void done(boolean success, String message);
    }

    @Override
    public void load() {
        billingClient = BillingClient.newBuilder(getContext())
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build();
    }

    @Override
    protected void handleOnDestroy() {
        if (billingClient != null && billingClient.isReady()) billingClient.endConnection();
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (billingClient == null || !billingClient.isReady()) return;
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build();
        billingClient.queryPurchasesAsync(params, (result, purchases) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
            Purchase owned = findOwnedPurchase(purchases);
            if (owned == null) return;
            acknowledge(owned, (success, message) -> {
                if (!success) return;
                JSObject response = new JSObject();
                response.put("owned", true);
                notifyListeners("entitlementChanged", response);
            });
        });
    }

    private void whenReady(PluginCall call, Runnable action) {
        if (billingClient == null) {
            call.reject("Google Play Billing no está disponible.");
            return;
        }
        if (billingClient.isReady()) {
            action.run();
            return;
        }
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) action.run();
                else call.reject("No se pudo conectar con Google Play: " + result.getDebugMessage());
            }

            @Override
            public void onBillingServiceDisconnected() {
                // The next user action reconnects automatically.
            }
        });
    }

    private Purchase findOwnedPurchase(List<Purchase> purchases) {
        if (purchases == null) return null;
        for (Purchase purchase : purchases) {
            if (purchase.getProducts().contains(PRODUCT_ID)
                && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                return purchase;
            }
        }
        return null;
    }

    private void acknowledge(Purchase purchase, Completion completion) {
        if (purchase.isAcknowledged()) {
            completion.done(true, "");
            return;
        }
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.getPurchaseToken())
            .build();
        billingClient.acknowledgePurchase(params, result -> completion.done(
            result.getResponseCode() == BillingClient.BillingResponseCode.OK,
            result.getDebugMessage()
        ));
    }

    private ProductDetails.OneTimePurchaseOfferDetails chooseOffer(ProductDetails details) {
        List<ProductDetails.OneTimePurchaseOfferDetails> offers = details.getOneTimePurchaseOfferDetailsList();
        if (offers != null && !offers.isEmpty()) {
            for (ProductDetails.OneTimePurchaseOfferDetails offer : offers) {
                if (offer.getRentalDetails() == null
                    && offer.getPreorderDetails() == null
                    && offer.getDiscountDisplayInfo() == null) return offer;
            }
            for (ProductDetails.OneTimePurchaseOfferDetails offer : offers) {
                if (offer.getRentalDetails() == null && offer.getPreorderDetails() == null) return offer;
            }
            return offers.get(0);
        }
        return details.getOneTimePurchaseOfferDetails();
    }

    private void queryProduct(PluginCall call, boolean owned) {
        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build();
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
            .setProductList(Collections.singletonList(product))
            .build();

        billingClient.queryProductDetailsAsync(params, (result, productResult) -> {
            JSObject response = new JSObject();
            response.put("owned", owned);
            response.put("productId", PRODUCT_ID);
            response.put("available", false);

            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                && !productResult.getProductDetailsList().isEmpty()) {
                ProductDetails details = productResult.getProductDetailsList().get(0);
                ProductDetails.OneTimePurchaseOfferDetails offer = chooseOffer(details);
                response.put("available", offer != null);
                if (offer != null) {
                    response.put("price", offer.getFormattedPrice());
                    response.put("currency", offer.getPriceCurrencyCode());
                    response.put("priceMicros", offer.getPriceAmountMicros());
                }
            }
            call.resolve(response);
        });
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        whenReady(call, () -> {
            QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
            billingClient.queryPurchasesAsync(params, (result, purchases) -> {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    call.reject("No se pudo consultar la compra: " + result.getDebugMessage());
                    return;
                }
                Purchase owned = findOwnedPurchase(purchases);
                if (owned == null) {
                    queryProduct(call, false);
                    return;
                }
                acknowledge(owned, (success, message) -> {
                    if (success) queryProduct(call, true);
                    else call.reject("No se pudo confirmar la compra: " + message);
                });
            });
        });
    }

    @PluginMethod
    public void restore(PluginCall call) {
        whenReady(call, () -> {
            QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
            billingClient.queryPurchasesAsync(params, (result, purchases) -> {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    call.reject("No se pudo restaurar la compra: " + result.getDebugMessage());
                    return;
                }
                Purchase owned = findOwnedPurchase(purchases);
                if (owned == null) {
                    JSObject response = new JSObject();
                    response.put("owned", false);
                    call.resolve(response);
                    return;
                }
                acknowledge(owned, (success, message) -> {
                    if (!success) {
                        call.reject("No se pudo confirmar la compra: " + message);
                        return;
                    }
                    JSObject response = new JSObject();
                    response.put("owned", true);
                    call.resolve(response);
                });
            });
        });
    }

    @PluginMethod
    public void purchase(PluginCall call) {
        whenReady(call, () -> {
            QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
            QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(product))
                .build();

            billingClient.queryProductDetailsAsync(params, (result, productResult) -> {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK
                    || productResult.getProductDetailsList().isEmpty()) {
                    call.reject("El desbloqueo todavía no está disponible en Google Play.");
                    return;
                }

                ProductDetails details = productResult.getProductDetailsList().get(0);
                ProductDetails.OneTimePurchaseOfferDetails offer = chooseOffer(details);
                if (offer == null) {
                    call.reject("No hay una opción de compra disponible.");
                    return;
                }

                BillingFlowParams.ProductDetailsParams productParams =
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offer.getOfferToken())
                        .build();
                BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(productParams))
                    .build();

                pendingPurchaseCall = call;
                getActivity().runOnUiThread(() -> {
                    BillingResult launch = billingClient.launchBillingFlow(getActivity(), flowParams);
                    if (launch.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        pendingPurchaseCall = null;
                        call.reject("No se pudo abrir Google Play: " + launch.getDebugMessage());
                    }
                });
            });
        });
    }

    @PluginMethod
    public void redeemCode(PluginCall call) {
        String code = call.getString("code", "").trim();
        if (code.isEmpty()) {
            call.reject("Introduce un código.");
            return;
        }
        Uri uri = Uri.parse("https://play.google.com/redeem?code=" + Uri.encode(code));
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setPackage("com.android.vending");
        try {
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception error) {
            intent.setPackage(null);
            try {
                getActivity().startActivity(intent);
                call.resolve();
            } catch (Exception fallbackError) {
                call.reject("No se pudo abrir Google Play.");
            }
        }
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result, List<Purchase> purchases) {
        PluginCall call = pendingPurchaseCall;
        if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            if (call != null) {
                JSObject response = new JSObject();
                response.put("owned", false);
                response.put("cancelled", true);
                call.resolve(response);
            }
            pendingPurchaseCall = null;
            return;
        }

        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            if (call != null) call.reject("La compra no se completó: " + result.getDebugMessage());
            pendingPurchaseCall = null;
            return;
        }

        Purchase purchase = findOwnedPurchase(purchases);
        if (purchase == null) {
            if (call != null) {
                JSObject response = new JSObject();
                response.put("owned", false);
                response.put("pending", true);
                call.resolve(response);
            }
            pendingPurchaseCall = null;
            return;
        }

        acknowledge(purchase, (success, message) -> {
            if (call != null) {
                if (success) {
                    JSObject response = new JSObject();
                    response.put("owned", true);
                    call.resolve(response);
                } else {
                    call.reject("No se pudo confirmar la compra: " + message);
                }
            }
            pendingPurchaseCall = null;
        });
    }
}
