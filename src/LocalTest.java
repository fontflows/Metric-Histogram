import ij.IJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import java.io.File;
import java.util.*;

/**
 * Teste local do extrator de Histograma Métrico.
 * Lê .bmp de test-images/, extrai vetores, exibe gráfico da referência e executa k-NN.
 *
 * Executar: mvn compile exec:java
 */
public class LocalTest {

    public static void main(String[] args) {
        // Carrega todas as imagens .bmp do diretório de teste
        File testDir = new File("test-images");
        File[] images = testDir.listFiles((d, n) -> n.toLowerCase().endsWith(".bmp"));

        if (images == null || images.length == 0) {
            System.out.println("Nenhuma imagem .bmp encontrada em test-images/");
            return;
        }

        // Ordena por nome para garantir reprodutibilidade
        Arrays.sort(images, Comparator.comparing(File::getName));
        Metric_Histogram extractor = new Metric_Histogram();

        // Primeira imagem é usada como referência para a busca
        ImagePlus ref = IJ.openImage(images[0].getAbsolutePath());
        if (ref == null) { System.out.println("Erro ao abrir: " + images[0].getName()); return; }

        // Extrai vetor de características da imagem de referência
        ByteProcessor bp  = ref.getProcessor().convertToByteProcessor();
        int[]    rawHist  = bp.getHistogram();
        double[] refVec   = extractor.extractFeatures(bp);
        ref.close();

        System.out.printf("Referencia: %s%n", images[0].getName());
        System.out.printf("Pontos de controle: %d%n", refVec.length / 2);
        System.out.println("Vetor: " + Arrays.toString(refVec));
        System.out.println();

        // Exibe comparação visual: histograma convencional vs métrico
        HistogramChart.show(images[0].getName(), rawHist, refVec);

        // Carrega demais imagens como base de busca
        List<Metric_Histogram.ImageEntry> entries = new ArrayList<>();
        for (int i = 1; i < images.length; i++) {
            ImagePlus img = IJ.openImage(images[i].getAbsolutePath());
            if (img == null) continue;
            double[] vec = extractor.extractFeatures(img.getProcessor().convertToByteProcessor());
            entries.add(new Metric_Histogram.ImageEntry(images[i].getName(), vec));
            img.close();
        }

        System.out.printf("Total de imagens de busca: %d%n%n", entries.size());

        // Executa k-NN com três funções de distância para comparar resultados
        int k = Math.min(5, entries.size());
        for (String dist : new String[]{"Euclidiana", "Manhattan", "Chebyshev"}) {
            List<Metric_Histogram.ImageEntry> copy = new ArrayList<>(entries);
            List<Metric_Histogram.ImageEntry> top  = extractor.knnSearch(refVec, copy, k, dist);
            System.out.println("--- Top " + k + " vizinhos (" + dist + ") ---");
            for (int i = 0; i < top.size(); i++) {
                Metric_Histogram.ImageEntry e = top.get(i);
                System.out.printf("  %d. %-30s dist=%.6f  pts=%d%n",
                        i + 1, e.name, e.distance, e.vector.length / 2);
            }
            System.out.println();
        }
    }
}
