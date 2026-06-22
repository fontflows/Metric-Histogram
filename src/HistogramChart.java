import javax.swing.*;
import java.awt.*;

/** 
 * Painel Swing que sobrepõe o histograma convencional (barras) ao histograma métrico (polilinha).
 * Permite comparação visual entre a representação completa e a comprimida.
 */
public class HistogramChart extends JPanel {

    // Dimensões do painel e espaçamento para eixos/rótulos
    private static final int W = 860, H = 440, PAD = 55;

    private final String title;
    private final int[]    hist; // histograma convencional (256 bins)
    private final double[] pts;  // pontos de controle [x0,y0, x1,y1, ...]

    public HistogramChart(String title, int[] hist, double[] pts) {
        this.title = title;
        this.hist  = hist;
        this.pts   = pts;
        setPreferredSize(new Dimension(W, H));
        setBackground(Color.WHITE);
    }

    /** Abre um JFrame com o gráfico. */
    public static void show(String title, int[] hist, double[] pts) {
        JFrame f = new JFrame("Histograma Métrico — " + title);
        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        f.add(new HistogramChart(title, hist, pts));
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        // Ativa anti-aliasing para suavizar linhas e curvas
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Calcula área disponível para o gráfico (descontando padding)
        int cW = W - 2 * PAD;
        int cH = H - 2 * PAD - 30;
        int top = PAD + 25;

        // Normaliza histograma para obter frequências relativas e encontra máximo
        long total = 0;
        for (int v : hist) total += v;
        double[] norm = new double[hist.length];
        double maxY = 0;
        for (int i = 0; i < hist.length; i++) {
            norm[i] = (double) hist[i] / total;
            if (norm[i] > maxY) maxY = norm[i];
        }

        // Desenha título
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f));
        g2.setColor(Color.BLACK);
        g2.drawString(title, PAD, PAD + 16);

        // Desenha barras do histograma convencional (fundo cinzento)
        // Cada barra representa a frequência de um nível de cinza
        int bw = Math.max(1, cW / hist.length);
        g2.setColor(new Color(190, 190, 210));
        for (int i = 0; i < hist.length; i++) {
            int bh = (int)(norm[i] / maxY * cH);
            g2.fillRect(PAD + i * cW / hist.length, top + cH - bh, bw, bh);
        }

        // Desenha polilinha dos pontos de controle do histograma métrico
        // Conexão linear entre poucos pontos aproxima bem a distribuição
        int nPts = pts.length / 2;
        int[] px = new int[nPts];
        int[] py = new int[nPts];
        for (int i = 0; i < nPts; i++) {
            // Mapeia coordenadas normalizadas [0,1] para pixel screen
            px[i] = PAD + (int)(pts[2 * i]     * cW);
            py[i] = top + cH - (int)(pts[2 * i + 1] / maxY * cH);
        }
        g2.setColor(new Color(200, 40, 40));
        g2.setStroke(new BasicStroke(2.2f));
        g2.drawPolyline(px, py, nPts);
        // Marca cada ponto de controle com um círculo
        for (int i = 0; i < nPts; i++)
            g2.fillOval(px[i] - 4, py[i] - 4, 9, 9);

        // Desenha eixos X e Y
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(PAD, top, PAD, top + cH);
        g2.drawLine(PAD, top + cH, PAD + cW, top + cH);

        // Rótulos dos eixos
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
        g2.drawString("Nível de cinza", PAD + cW / 2 - 35, top + cH + 28);
        g2.drawString("0",   PAD - 8,       top + cH + 16);
        g2.drawString("255", PAD + cW - 12, top + cH + 16);

        // Legenda para diferenciar as duas representações
        int lx = PAD + cW - 230, ly = top + 8;
        g2.setColor(new Color(190, 190, 210));
        g2.fillRect(lx, ly, 13, 13);
        g2.setColor(Color.BLACK);
        g2.drawString("Histograma convencional", lx + 18, ly + 11);
        g2.setColor(new Color(200, 40, 40));
        g2.fillOval(lx, ly + 22, 13, 13);
        g2.setColor(Color.BLACK);
        g2.drawString("Histograma métrico (" + nPts + " pts)", lx + 18, ly + 33);
    }
}
