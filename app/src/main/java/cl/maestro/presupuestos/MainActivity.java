package cl.maestro.presupuestos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.window.OnBackInvokedDispatcher;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * App "Maestro Presupuestos": abre index.html (incluido dentro del APK) y le da acceso a
 * compartir por WhatsApp, enviar texto y guardar el presupuesto como PDF.
 * Funciona sin internet; los presupuestos quedan guardados en el teléfono.
 */
public class MainActivity extends Activity {

    private static final String DOMINIO = "appassets.androidplatform.net";
    private static final String INICIO = "https://" + DOMINIO + "/assets/index.html";

    private WebView web;
    private WebViewAssetLoader cargador;
    private static final int PEDIR_ARCHIVO = 7;
    private ValueCallback<Uri[]> esperaArchivo;   // para "Adjuntar logo"

    // ---- Suscripción (Google Play) ----
    // ID de la suscripción creada en Play Console > Monetizar > Suscripciones
    private static final String SUSCRIPCION = "maestro_pro";
    private BillingClient pagos;
    private volatile ProductDetails producto;          // planes y precios que entrega Google
    private volatile boolean suscrito = false;
    private volatile boolean planesListos = false;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);

        cargador = new WebViewAssetLoader.Builder()
                .setDomain(DOMINIO)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        FrameLayout raiz = new FrameLayout(this);
        web = new WebView(this);
        raiz.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(raiz);

        // Android 15+ dibuja la app detrás de la barra de estado y la de navegación.
        // Se deja un margen del tamaño de esas barras (y del teclado) para que nada quede tapado.
        raiz.setBackgroundColor(0xFF16181B);
        if (Build.VERSION.SDK_INT >= 35) {
            raiz.setOnApplyWindowInsetsListener((v, ins) -> {
                Insets m = ins.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
                v.setPadding(m.left, m.top, m.right, m.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        // Botón/gesto "Atrás" en Android 13 o más nuevo
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::manejarAtras);
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // aquí se guardan los presupuestos
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // Funciones que la página puede usar: Android.compartir(), Android.abrir(), Android.imprimir()
        web.addJavascriptInterface(new Puente(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest pedido) {
                return cargador.shouldInterceptRequest(pedido.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest pedido) {
                Uri u = pedido.getUrl();
                if (DOMINIO.equals(u.getHost())) return false;
                abrirFuera(u);
                return true;
            }
        });

        // Permite elegir una imagen de la galería (botón "Adjuntar logo")
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (esperaArchivo != null) esperaArchivo.onReceiveValue(null);
                esperaArchivo = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Elegir logo"), PEDIR_ARCHIVO);
                } catch (ActivityNotFoundException e) {
                    esperaArchivo = null;
                    Toast.makeText(MainActivity.this, "No hay una app para elegir imágenes.", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        iniciarPagos();

        if (estado != null) web.restoreState(estado);
        else web.loadUrl(INICIO);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Al volver a la app revisa si la suscripción se renovó o se canceló
        if (pagos != null && pagos.isReady()) consultarCompras();
    }

    // ================= SUSCRIPCIÓN =================

    private void iniciarPagos() {
        pagos = BillingClient.newBuilder(this)
                .setListener(this::alTerminarCompra)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
        pagos.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult r) {
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    consultarPlanes();
                    consultarCompras();
                } else {
                    planesListos = true;
                    avisarPagina();
                }
            }

            @Override
            public void onBillingServiceDisconnected() { }
        });
    }

    private void consultarPlanes() {
        QueryProductDetailsParams p = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SUSCRIPCION)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()))
                .build();
        pagos.queryProductDetailsAsync(p, (r, resultado) -> {
            List<ProductDetails> lista = resultado.getProductDetailsList();
            if (lista != null && !lista.isEmpty()) producto = lista.get(0);
            planesListos = true;
            avisarPagina();
        });
    }

    private void consultarCompras() {
        pagos.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS).build(), (r, compras) -> {
            if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
            boolean activo = false;
            for (Purchase c : compras) if (procesarCompra(c)) activo = true;
            suscrito = activo;
            avisarPagina();
        });
    }

    /** Respuesta de Google después de que el usuario paga (o cancela). */
    private void alTerminarCompra(BillingResult r, List<Purchase> compras) {
        int codigo = r.getResponseCode();
        if (codigo == BillingClient.BillingResponseCode.OK && compras != null) {
            for (Purchase c : compras) if (procesarCompra(c)) suscrito = true;
            avisarPagina();
            if (suscrito) runOnUiThread(() -> Toast.makeText(this, "¡Gracias! Tu suscripción está activa.", Toast.LENGTH_LONG).show());
        } else if (codigo == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            consultarCompras();
        } else if (codigo != BillingClient.BillingResponseCode.USER_CANCELED) {
            runOnUiThread(() -> Toast.makeText(this, "No se pudo completar el pago. Intenta de nuevo.", Toast.LENGTH_LONG).show());
        }
    }

    /** Confirma el pago ante Google (si no se confirma en 3 días, Google lo devuelve). */
    private boolean procesarCompra(Purchase c) {
        if (c.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return false;
        if (!c.getProducts().contains(SUSCRIPCION)) return false;
        if (!c.isAcknowledged()) {
            pagos.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(c.getPurchaseToken()).build(), r -> { });
        }
        return true;
    }

    /** Estado de la suscripción en texto JSON para la página. */
    private String estadoJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("activo", suscrito);
            o.put("listo", planesListos);
            JSONArray planes = new JSONArray();
            ProductDetails pd = producto;
            if (pd != null && pd.getSubscriptionOfferDetails() != null) {
                for (ProductDetails.SubscriptionOfferDetails of : pd.getSubscriptionOfferDetails()) {
                    if (of.getOfferId() != null) continue;   // solo el plan base de cada período
                    List<ProductDetails.PricingPhase> fases = of.getPricingPhases().getPricingPhaseList();
                    if (fases.isEmpty()) continue;
                    ProductDetails.PricingPhase f = fases.get(fases.size() - 1);
                    JSONObject x = new JSONObject();
                    x.put("id", of.getBasePlanId());
                    x.put("precio", f.getFormattedPrice());
                    x.put("periodo", f.getBillingPeriod());     // P1M = mensual, P1Y = anual
                    x.put("micros", f.getPriceAmountMicros());
                    planes.put(x);
                }
            }
            o.put("planes", planes);
            return o.toString();
        } catch (Exception e) {
            return "{\"activo\":false,\"listo\":true,\"planes\":[]}";
        }
    }

    private void avisarPagina() {
        String js = "window.onSuscripcion && window.onSuscripcion(" + JSONObject.quote(estadoJson()) + ")";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private void comprar(String planId) {
        ProductDetails pd = producto;
        if (pd == null || pd.getSubscriptionOfferDetails() == null) {
            Toast.makeText(this, "Los planes aún no cargan. Revisa tu conexión e intenta de nuevo.", Toast.LENGTH_LONG).show();
            if (pagos != null && pagos.isReady()) consultarPlanes();
            return;
        }
        String token = null;
        for (ProductDetails.SubscriptionOfferDetails of : pd.getSubscriptionOfferDetails()) {
            if (planId.equals(of.getBasePlanId()) && of.getOfferId() == null) { token = of.getOfferToken(); break; }
        }
        if (token == null) return;
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(pd)
                                .setOfferToken(token)
                                .build()))
                .build();
        pagos.launchBillingFlow(this, params);
    }

    @Override
    protected void onSaveInstanceState(Bundle salida) {
        super.onSaveInstanceState(salida);
        web.saveState(salida);
    }

    @Override
    protected void onActivityResult(int pedido, int resultado, Intent datos) {
        if (pedido == PEDIR_ARCHIVO) {
            if (esperaArchivo != null) {
                esperaArchivo.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultado, datos));
                esperaArchivo = null;
            }
            return;
        }
        super.onActivityResult(pedido, resultado, datos);
    }

    /** Lo que la página puede pedirle al teléfono. */
    private class Puente {
        @JavascriptInterface
        public void compartir(String texto) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, texto);
                try {
                    startActivity(Intent.createChooser(i, "Enviar con…"));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "No hay apps para compartir.", Toast.LENGTH_LONG).show();
                }
            });
        }

        /** Estado actual de la suscripción (JSON). */
        @JavascriptInterface
        public String estadoSuscripcion() {
            return estadoJson();
        }

        /** Abre la ventana de pago de Google Play para el plan elegido (ej. "mensual"). */
        @JavascriptInterface
        public void suscribir(String planId) {
            runOnUiThread(() -> comprar(planId == null ? "" : planId));
        }

        @JavascriptInterface
        public void abrir(String url) {
            runOnUiThread(() -> abrirFuera(Uri.parse(url)));
        }

        @JavascriptInterface
        public void imprimir(String titulo) {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (pm == null) return;
                String nombre = (titulo == null || titulo.trim().isEmpty()) ? "Presupuesto" : titulo.trim();
                PrintDocumentAdapter adaptador = web.createPrintDocumentAdapter(nombre);
                pm.print(nombre, adaptador, new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .build());
            });
        }
    }

    private void abrirFuera(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No hay una app para abrir este enlace (¿está instalado WhatsApp?).", Toast.LENGTH_LONG).show();
        }
    }

    /** "Atrás": primero cierra ventanas y vuelve de pantalla dentro de la app; en el inicio pregunta antes de salir. */
    @Override
    public void onBackPressed() {   // Android 12 o más antiguo
        manejarAtras();
    }

    private void manejarAtras() {
        web.evaluateJavascript("(window.atras && window.atras()) ? 'si' : 'no'", valor -> {
            if (valor != null && valor.contains("si")) return;
            new AlertDialog.Builder(this)
                    .setMessage("¿Salir de Maestro?")
                    .setPositiveButton("Salir", (d, w) -> finish())
                    .setNegativeButton("Quedarme", null)
                    .show();
        });
    }

    @Override
    protected void onDestroy() {
        if (pagos != null) pagos.endConnection();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
