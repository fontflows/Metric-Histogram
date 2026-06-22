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

    // Limiar de tolerância: quanto maior o erro de área, menos comprimida a curva
    // Valores baixos (0.05) geram mais pontos de controle, preservando detalhes
    private static final double AREA_ERROR_THRESHOLD = 0.05;

    @Override
    public void run(String arg) {
        // Obtém a imagem aberta no ImageJ para usar como referência
        ImagePlus refImg = IJ.getImage();
        if (refImg == null) {
            IJ.error("Abra uma imagem de referência primeiro.");
            return;
        }

        // Solicita ao usuário a pasta contendo as imagens de busca
        String folder = IJ.getDirectory("Selecione a pasta com as imagens de busca");
        if (folder == null) return;

        // Extrai vetor de características da imagem de referência usando Histograma Métrico
        IJ.showStatus("Extraindo vetor da imagem de referência...");
        double[] refVec = extractFeatures(refImg.getProcessor().convertToByteProcessor());

        // Carrega todas as imagens suportadas (.bmp, .png, .jpg, etc.) da pasta
        File dir = new File(folder);
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().matches(".*\\.(bmp|png|jpg|jpeg|tif|tiff|gif)"));
        if (files == null || files.length == 0) {
            IJ.error("Nenhuma imagem encontrada na pasta selecionada.");
            return;
        }

        // Processa cada imagem: abre, extrai vetor, fecha
        List<ImageEntry> entries = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            IJ.showProgress(i, files.length);
            ImagePlus img = IJ.openImage(files[i].getAbsolutePath());
            if (img == null) continue;
            double[] vec = extractFeatures(img.getProcessor().convertToByteProcessor());
            entries.add(new ImageEntry(files[i].getName(), vec));
            img.close();
        }

        // Salva todos os vetores para auditoria e análise posterior
        saveVectors(refImg.getTitle(), refVec, entries);

        // Diálogo para configurar k (número de vizinhos) e função de distância
        GenericDialog gd = new GenericDialog("Busca K-NN");
        gd.addNumericField("Valor de k:", 5, 0);
        String[] distFuncs = {"Euclidiana", "Manhattan", "Chebyshev"};
        gd.addChoice("Função de distância:", distFuncs, distFuncs[0]);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        int k = Math.max(1, (int) gd.getNextNumber());
        String distFunc = gd.getNextChoice();

        // Executa k-NN e exibe resultados no log e arquivo
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
     *   <li>Normaliza o histograma convencional (256 bins) por frequência total;</li>
     *   <li>Inicia um ponto de controle no primeiro bin (x=0);</li>
     *   <li>Usa estratégia greedy: expande intervalos enquanto o erro de área ficar abaixo do limiar;</li>
     *   <li>Quando o erro seria violado, o ponto anterior é marcado como de controle e o intervalo recomeça;</li>
     *   <li>Garante que o último ponto (x=255) sempre está presente;</li>
     *   <li>Resultado: representação comprimida da distribuição com poucos pontos.</li>
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

        // Normaliza para obter frequências relativas (soma = 1)
        double[] h = new double[n];
        for (int i = 0; i < n; i++) h[i] = (double) hist[i] / total;

        // Inicia lista de pontos de controle com o primeiro ponto
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{0, h[0]});

        // Estratégia greedy: expande cada intervalo o máximo possível antes de criar novo ponto
        int start = 0;
        while (start < n - 1) {
            int end = start + 1;
            // Encontra o maior intervalo [start, j] que ainda respeita o limiar de erro
            for (int j = start + 2; j < n; j++) {
                if (areaError(h, start, j) <= AREA_ERROR_THRESHOLD) {
                    end = j;
                } else {
                    // Uma vez que o erro é violado, intervalos maiores também violarão
                    break;
                }
            }
            // Se conseguiu expandir além de start+1, registra novo ponto de controle
            if (end > start) {
                pts.add(new double[]{end, h[end]});
                start = end;
            } else {
                break;
            }
        }

        // Garante que o último ponto (x=255) sempre está na lista
        if (((int) pts.get(pts.size() - 1)[0]) != n - 1) {
            pts.add(new double[]{n - 1, h[n - 1]});
        }

        // Converte para vetor com coordenadas normalizadas (x em [0,1], y como frequência)
        double[] vec = new double[pts.size() * 2];
        for (int i = 0; i < pts.size(); i++) {
            vec[2 * i]     = pts.get(i)[0] / 255.0;  // nível de cinza normalizado
            vec[2 * i + 1] = pts.get(i)[1];           // frequência relativa
        }
        return vec;
    }

    /**
     * Calcula o erro de área entre o histograma real e a interpolação linear
     * entre os pontos {@code start} e {@code end}.
     *
     * Compara:
     * - realArea: soma das frequências reais no intervalo [start, end]
     * - linearArea: área sob a linha reta conectando (start, h[start]) e (end, h[end])
     *
     * Usado na estratégia greedy para determinar quando interromper a expansão de um intervalo.
     */
    private double areaError(double[] h, int start, int end) {
        // Soma as frequências reais no intervalo
        double realArea = 0;
        for (int i = start; i <= end; i++) realArea += h[i];
        // Estima a área sob a linha reta (trapézio com altura média)
        double linearArea = (end - start + 1) * (h[start] + h[end]) / 2.0;
        return Math.abs(realArea - linearArea);
    }

    /**
     * Reconstrói o histograma aproximado com 256 bins via interpolação linear
     * entre os pontos de controle.
     *
     * Propósito: normalizar o tamanho dos vetores antes de calcular distâncias,
     * permitindo comparar vetores com números diferentes de pontos de controle.
     * Isso elimina problemas de padding com zeros ou artefatos de tamanho.
     *
     * @param vec vetor de pontos de controle [x0,y0, x1,y1, ...]
     * @return histograma interpolado com exatamente 256 bins
     */
    private double[] resample(double[] vec) {
        int nPts = vec.length / 2;
        double[] result = new double[256];

        for (int bin = 0; bin < 256; bin++) {
            // Normaliza posição do bin para [0, 1] (corresponde a nível de cinza / 255)
            double x = bin / 255.0;

            // Encontra os dois pontos de controle que envolvem x
            // (busca linear, poderia ser binary search em vetores muito grandes)
            int lo = 0, hi = nPts - 1;
            for (int i = 0; i < nPts - 1; i++) {
                if (vec[2 * i] <= x && x <= vec[2 * (i + 1)]) {
                    lo = i;
                    hi = i + 1;
                    break;
                }
            }

            // Interpola linearmente entre os dois pontos encontrados
            double x0 = vec[2 * lo],     y0 = vec[2 * lo + 1];
            double x1 = vec[2 * hi],     y1 = vec[2 * hi + 1];

            result[bin] = (x1 == x0) ? y0 : y0 + (y1 - y0) * (x - x0) / (x1 - x0);
        }
        return result;
    }

    /**
     * Calcula a distância entre dois vetores de características usando a função especificada.
     *
     * Ambos os vetores são reamostrados para 256 bins via interpolação linear antes
     * da comparação, normalizando tamanhos diferentes e permitindo comparações justas.
     *
     * @param a        vetor da imagem de referência
     * @param b        vetor da imagem de busca
     * @param distFunc "Euclidiana" (raiz da soma de quadrados),
     *                 "Manhattan" (soma de valores absolutos),
     *                 "Chebyshev" (máxima diferença absoluta)
     * @return valor da distância
     */
    double computeDistance(double[] a, double[] b, String distFunc) {
        // Normaliza ambos os vetores para 256 bins antes de comparar
        double[] ra = resample(a);
        double[] rb = resample(b);

        switch (distFunc) {
            case "Manhattan": {
                // Soma das diferenças absolutas: útil para distribuições não-gaussianas
                // Penaliza desvios proporcionalmente sem amplificar grandes diferenças
                double d = 0;
                for (int i = 0; i < 256; i++) d += Math.abs(ra[i] - rb[i]);
                return d;
            }
            case "Chebyshev": {
                // Máxima diferença absoluta: robusta a outliers
                // Penaliza principalmente o pior desvio em qualquer posição
                double d = 0;
                for (int i = 0; i < 256; i++) d = Math.max(d, Math.abs(ra[i] - rb[i]));
                return d;
            }
            default: { // Euclidiana
                // Raiz da soma dos quadrados: métrica mais comum em ciência de dados
                // Amplifica grandes diferenças, sensível a distribuições muito diferentes
                double d = 0;
                for (int i = 0; i < 256; i++) d += (ra[i] - rb[i]) * (ra[i] - rb[i]);
                return Math.sqrt(d);
            }
        }
    }

    /**
     * Executa a busca k-NN (k-vizinhos mais próximos).
     *
     * Algoritmo:
     * <ol>
     *   <li>Calcula a distância da imagem de referência para todas as imagens da lista;</li>
     *   <li>Ordena as imagens por distância crescente;</li>
     *   <li>Retorna apenas as k primeiras (vizinhos mais próximos).</li>
     * </ol>
     *
     * @param refVec   vetor da imagem de referência
     * @param entries  lista de imagens de busca com seus vetores
     * @param k        número de vizinhos a retornar
     * @param distFunc função de distância a usar
     * @return sublista com os k vizinhos mais próximos (ordenados por distância crescente)
     */
    List<ImageEntry> knnSearch(double[] refVec, List<ImageEntry> entries, int k, String distFunc) {
        // Calcula distância para cada entrada em relação à referência
        for (ImageEntry e : entries) e.distance = computeDistance(refVec, e.vector, distFunc);
        // Ordena por distância crescente (mais próximos primeiro)
        entries.sort(Comparator.comparingDouble(e -> e.distance));
        // Retorna apenas os k primeiros
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
