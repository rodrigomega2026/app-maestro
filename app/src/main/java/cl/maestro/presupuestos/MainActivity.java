package cl.maestro.presupuestos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.view.ViewGroup;
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

        if (estado != null) web.restoreState(estado);
        else web.loadUrl(INICIO);
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
    public void onBackPressed() {
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
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
