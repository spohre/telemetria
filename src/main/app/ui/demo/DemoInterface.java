package app.ui.demo;

import app.ui.tema.Tema;
import app.ui.telas.JanelaPrincipal;

import javax.swing.SwingUtilities;

/**
 * Abre a interface com o {@link ControladorSimulado}.
 * <pre>mvn -q compile exec:java -Dexec.mainClass=app.ui.demo.DemoInterface</pre>
 * No Main real: troque {@code new ControladorSimulado()} pelo controlador definitivo.
 */
public final class DemoInterface {

    private DemoInterface() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {          // toda a criação de tela na EDT
            Tema.instalar();
            new JanelaPrincipal(new ControladorSimulado()).setVisible(true);
        });
    }
}
