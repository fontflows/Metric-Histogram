import ij.*;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;
import java.io.*;
import java.util.*;

/**
 * Plugin ImageJ para extração de características via Histograma Métrico
 * e busca por k-vizinhos mais próximos (k-NN).
 *
 * <p>Fluxo: abre imagem de referência → seleciona pasta de busca →
 * extrai vetores de características → executa k-NN com função de distância escolhida.</p>
 */
public class Metric_Histogram implements PlugIn {

    /** Limiar de erro de área para determinar novos pontos de controle. */
    private static final double AREA_ERROR_THRESHOLD = 0.05;

    @Override
    public void run(String arg) {
        ImagePlus refImg = IJ.getImage();
        if (refImg == null) {
            IJ.error("Abra uma imagem de referência primeiro.");
            return;
        }

        String folder = IJ.getDirectory("Selecione a pasta com as imagens de busca");
        if (folder == null) return;

        IJ.showStatus("Extraindo vetor da imagem de referência...");
        double[] refVec = extractFeatures(refImg.getProcessor().convertToByteProcessor());

        File dir = new File(folder);
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().matches(".*\\.(bmp|png|jpg|jpeg|tif|tiff|gif)"));
        if (files == null || files.length == 0) {
            IJ.error("Nenhuma imagem encontrada na pasta selecionada.");
            return;
        }

        List<ImageEntry> entries = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            IJ.showProgress(i, files.length);
            ImagePlus img = IJ.openImage(files[i].getAbsolutePath());
            if (img == null) continue;
            double[] vec = extractFeatures(img.getProcessor().convertToByteProcessor());
            entries.add(new ImageEntry(files[i].getName(), vec));
            img.close();
        }

        saveVectors(refImg.getTitle(), refVec, entries);

        GenericDialog gd = new GenericDialog("Busca K-NN");
        gd.addNumericField("Valor de k:", 5, 0);
        String[] distFuncs = {"Euclidiana", "Manhattan", "Chebyshev"};
        gd.addChoice("Função de distância:", distFuncs, distFuncs[0]);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        int k = Math.max(1, (int) gd.getNextNumber());
        String distFunc = gd.getNextChoice();

        List<ImageEntry> results = knnSearch(refVec, entries, k, distFunc);
        showResults(refImg.getTitle(), refVec, results, distFunc);
    }

    /**
     * Extrai o vetor de características de uma imagem em níveis de cinza
     * usando o algoritmo de Histograma Métrico.
     *
     * @param bp processador de imagem em 8 bits
     * @return vetor de características com coordenadas [x0,y0, x1,y1, ...] dos pontos de controle
     */
    double[] extractFeatures(ByteProcessor bp) {
        return computeMetricHistogram(bp.getHistogram());
    }

    /**
     * Calcula o Histograma Métrico a partir do histograma convencional.
     *
     * <p>Algoritmo:
     * <ol>
     *   <li>Normaliza o histograma convencional (256 bins);</li>
     *   <li>Percorre os bins e avalia o erro de área entre a curva real e a interpolação linear;</li>
     *   <li>Quando o erro supera o limiar, o bin anterior é registrado como ponto de controle;</li>
     *   <li>O vetor final contém as coordenadas (nível de cinza normalizado, frequência) de cada ponto.</li>
     * </ol>
     * </p>
     *
     * @param hist histograma convencional com 256 bins
     * @return vetor de pontos de controle intercalados [x0,y0, x1,y1, ...]
     */
    double[] computeMetricHistogram(int[] hist) {
        int n = hist.length;
        long total = 0;
        for (int v : hist) total += v;

        double[] h = new double[n];
        for (int i = 0; i < n; i++) h[i] = (double) hist[i] / total;

        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{0, h[0]});

        int start = 0;
        while (start < n - 1) {
            int end = start + 1;
            for (int j = start + 2; j < n; j++) {
                if (areaError(h, start, j) <= AREA_ERROR_THRESHOLD) {
                    end = j;
                }
                else {
                    break;
                }
            }
            if (end > start) {
                pts.add(new double[]{end, h[end]});
                start = end;
            } else {
                break;
            }
        }

        if (((int) pts.get(pts.size() - 1)[0]) != n - 1) {
            pts.add(new double[]{n - 1, h[n - 1]});
        }

        double[] vec = new double[pts.size() * 2];
        for (int i = 0; i < pts.size(); i++) {
            vec[2 * i]     = pts.get(i)[0] / 255.0;
            vec[2 * i + 1] = pts.get(i)[1];
        }
        return vec;
    }

    /**
     * Calcula o erro de área entre o histograma real e a interpolação linear
     * entre os pontos {@code start} e {@code end}.
     */
    private double areaError(double[] h, int start, int end) {
        double realArea = 0;
        for (int i = start; i <= end; i++) realArea += h[i];
        double linearArea = (end - start + 1) * (h[start] + h[end]) / 2.0;
        return Math.abs(realArea - linearArea);
    }

    /**
     * Reconstrói o histograma aproximado com 256 bins via interpolação linear
     * entre os pontos de controle. Usado para normalizar o tamanho antes de
     * calcular distâncias, evitando artefatos do padding com zeros.
     *
     * @param vec vetor de pontos de controle [x0,y0, x1,y1, ...]
     * @return histograma interpolado com exatamente 256 bins
     */
    private double[] resample(double[] vec) {
        int nPts = vec.length / 2;
        double[] result = new double[256];

        for (int bin = 0; bin < 256; bin++) {
            double x = bin / 255.0;

            // encontra os dois pontos de controle que envolvem x
            int lo = 0, hi = nPts - 1;
            for (int i = 0; i < nPts - 1; i++) {
                if (vec[2 * i] <= x && x <= vec[2 * (i + 1)]) {
                    lo = i;
                    hi = i + 1;
                    break;
                }
            }

            double x0 = vec[2 * lo],     y0 = vec[2 * lo + 1];
            double x1 = vec[2 * hi],     y1 = vec[2 * hi + 1];

            result[bin] = (x1 == x0) ? y0 : y0 + (y1 - y0) * (x - x0) / (x1 - x0);
        }
        return result;
    }

    /**
     * Calcula a distância entre dois vetores de características.
     * Ambos são reamostrados para 256 bins via interpolação linear antes
     * da comparação, eliminando problemas de vetores com tamanhos diferentes.
     *
     * @param a        vetor da imagem de referência
     * @param b        vetor da imagem de busca
     * @param distFunc "Euclidiana", "Manhattan" ou "Chebyshev"
     * @return valor da distância
     */
    double computeDistance(double[] a, double[] b, String distFunc) {
        double[] ra = resample(a);
        double[] rb = resample(b);

        switch (distFunc) {
            case "Manhattan": {
                double d = 0;
                for (int i = 0; i < 256; i++) d += Math.abs(ra[i] - rb[i]);
                return d;
            }
            case "Chebyshev": {
                double d = 0;
                for (int i = 0; i < 256; i++) d = Math.max(d, Math.abs(ra[i] - rb[i]));
                return d;
            }
            default: { // Euclidiana
                double d = 0;
                for (int i = 0; i < 256; i++) d += (ra[i] - rb[i]) * (ra[i] - rb[i]);
                return Math.sqrt(d);
            }
        }
    }

    /**
     * Executa a busca k-NN: ordena as entradas por distância em relação ao vetor de referência.
     *
     * @param refVec   vetor da imagem de referência
     * @param entries  lista de imagens de busca com seus vetores
     * @param k        número de vizinhos
     * @param distFunc função de distância a usar
     * @return sublista com os k vizinhos mais próximos
     */
    List<ImageEntry> knnSearch(double[] refVec, List<ImageEntry> entries, int k, String distFunc) {
        for (ImageEntry e : entries) e.distance = computeDistance(refVec, e.vector, distFunc);
        entries.sort(Comparator.comparingDouble(e -> e.distance));
        return entries.subList(0, Math.min(k, entries.size()));
    }

    /** Salva todos os vetores de características em {@code feature_vectors.txt}. */
    void saveVectors(String refName, double[] refVec, List<ImageEntry> entries) {
        try (PrintWriter pw = new PrintWriter("feature_vectors.txt")) {
            pw.println("=== Referencia: " + refName + " ===");
            pw.println(vecToString(refVec));
            pw.println("\n=== Imagens de Busca ===");
            for (ImageEntry e : entries)
                pw.println(e.name + ": " + vecToString(e.vector));
            IJ.log("Vetores salvos em feature_vectors.txt");
        } catch (IOException ex) {
            IJ.error("Erro ao salvar vetores: " + ex.getMessage());
        }
    }

    /** Exibe os resultados k-NN no log do ImageJ e salva em {@code knn_results.txt}. */
    void showResults(String refName, double[] refVec, List<ImageEntry> results, String distFunc) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== RESULTADOS K-NN ===\n\n")
                .append("Referencia: ").append(refName).append("\n")
                .append("Vetor (").append(refVec.length / 2).append(" pontos de controle): ")
                .append(vecToString(refVec)).append("\n")
                .append("Funcao de distancia: ").append(distFunc).append("\n\n")
                .append("--- ").append(results.size()).append(" vizinho(s) mais proximo(s) ---\n");

        for (int i = 0; i < results.size(); i++) {
            ImageEntry e = results.get(i);
            sb.append(String.format("%d. %s  dist=%.6f  pontos=%d%n",
                    i + 1, e.name, e.distance, e.vector.length / 2));
            sb.append("   Vetor: ").append(vecToString(e.vector)).append("\n");
        }

        IJ.log(sb.toString());
        try (PrintWriter pw = new PrintWriter("knn_results.txt")) {
            pw.print(sb);
        } catch (IOException ignored) {}
    }

    private String vecToString(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(String.format("%.5f", v[i]));
            if (i < v.length - 1) sb.append(", ");
        }
        return sb.append("]").toString();
    }

    /** Representa uma imagem de busca com seu vetor de características e distância calculada. */
    static class ImageEntry {
        final String name;
        final double[] vector;
        double distance;

        ImageEntry(String name, double[] vector) {
            this.name = name;
            this.vector = vector;
        }
    }
}