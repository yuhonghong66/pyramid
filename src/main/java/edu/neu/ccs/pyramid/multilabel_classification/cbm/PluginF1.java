package edu.neu.ccs.pyramid.multilabel_classification.cbm;

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;
import edu.neu.ccs.pyramid.dataset.DataSetUtil;
import edu.neu.ccs.pyramid.dataset.MultiLabel;
import edu.neu.ccs.pyramid.dataset.MultiLabelClfDataSet;
import edu.neu.ccs.pyramid.multilabel_classification.PluginPredictor;
import edu.neu.ccs.pyramid.multilabel_classification.plugin_rule.GeneralF1Predictor;
import edu.neu.ccs.pyramid.util.Pair;
import org.apache.mahout.math.Matrix;
import org.apache.mahout.math.Vector;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

/**
 * Created by chengli on 4/9/16.
 */
public class PluginF1 implements PluginPredictor<CBM>{
    CBM cbm;
    private String predictionMode = "support";
    private int numSamples = 1000;
    private List<MultiLabel> support;
    private CBMIsotonicScaling cbmIsotonicScaling;
    private PMatrixIsotonicScaling pMatrixIsotonicScaling;

    private double piThreshold = 0.001;

    private int maxSize = 20;

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public PluginF1(CBM model, List<MultiLabel> support, MultiLabelClfDataSet dataSet, boolean isPair) {
        this.cbm = model;
        this.support = support;
        if (isPair) {
            this.pMatrixIsotonicScaling = new PMatrixIsotonicScaling(cbm, dataSet);
        }
    }

    public PluginF1(CBM model, List<MultiLabel> support, MultiLabelClfDataSet dataSet) {
        this.cbm = model;
        this.support = support;
        this.cbmIsotonicScaling = new CBMIsotonicScaling(cbm, dataSet);
    }

    public PluginF1(CBM model) {
        this.cbm = model;
    }

    public PluginF1(CBM cbm, List<MultiLabel> support) {
        this.cbm = cbm;
        this.support = support;
    }

    public void setNumSamples(int numSamples) {
        this.numSamples = numSamples;
    }

    public void setPredictionMode(String predictionMode) {
        this.predictionMode = predictionMode;
    }

    public String getPredictionMode() {
        return predictionMode;
    }

    public void setSupport(List<MultiLabel> support) {
        this.support = support;
    }


    public void setPiThreshold(double piThreshold) {
        this.piThreshold = piThreshold;
    }

    @Override
    public MultiLabel predict(Vector vector) {
        MultiLabel pred = null;
        switch (predictionMode){
            case "support":
                pred =  predictBySupport(vector);
                break;
            case "sampling":
                pred =  predictBySampling(vector);
                break;
            case "samplingNonEmpty":
                pred =  predictBySamplingNonEmpty(vector);
                break;
            case "isotonic":
                pred = predictByIsotonic(vector);
                break;
            case "pmatrix":
                pred = predictByPMatrix(vector);
                break;
            default:
                throw new IllegalArgumentException("unknown mode");
        }
        return pred;
    }

    private MultiLabel predictByPMatrix(Vector vector) {
        if (this.pMatrixIsotonicScaling == null) {
            throw new RuntimeException("pMatrixIsotonicScaling is not defined.");
        }
        double[] probs = cbm.predictAssignmentProbs(vector, support);
        GeneralF1Predictor generalF1Predictor = new GeneralF1Predictor();
        double[][] p = generalF1Predictor.getPMatrix(cbm.getNumClasses(), support,
                DoubleStream.of(probs).boxed().collect(Collectors.toList()));
        for (int i=0; i<p.length; i++) {
            for (int j=0; j<p[i].length; j++) {
                p[i][j] = pMatrixIsotonicScaling.calibratedProb(p[i][j]);
            }
        }
        double zeroProb = 0;
        for (int i=0;i<support.size();i++){
            if (support.get(i).getMatchedLabels().size()==0){
                zeroProb = probs[i];
                break;
            }
        }
        return generalF1Predictor.predictWithPMatrix(p, zeroProb);
    }

    private MultiLabel predictByIsotonic(Vector vector) {
        if (this.cbmIsotonicScaling == null) {
            throw new RuntimeException("CBMIsotonicScaling is not initialized yet!");
        }

        double[] probs = new double[support.size()];
        for (int c=0; c<support.size(); c++) {
            probs[c] = cbmIsotonicScaling.calibratedProb(vector, support.get(c));
        }
        GeneralF1Predictor generalF1Predictor = new GeneralF1Predictor();

        return generalF1Predictor.predict(cbm.getNumClasses(), support, probs);
    }

    private MultiLabel predictBySampling(Vector vector){
        List<MultiLabel> samples = cbm.samples(vector, numSamples);
        GeneralF1Predictor generalF1Predictor = new GeneralF1Predictor();
        generalF1Predictor.setMaxSize(maxSize);
        return generalF1Predictor.predict(cbm.getNumClasses(), samples);
//      unique the sample set and apply GFM
//        List<MultiLabel> uniqueSamples = new ArrayList(new HashSet(samples));
//        List<Double> probs = cbm.predictAssignmentProbs(vector, uniqueSamples);
//        return GeneralF1Predictor.predict(cbm.getNumClasses(), uniqueSamples, probs);
    }


    private MultiLabel predictBySamplingNonEmpty(Vector vector){
        List<MultiLabel> samples = cbm.samples(vector, numSamples);
        List<MultiLabel> nonZeros = samples.stream().filter(a->a.getNumMatchedLabels()>0).collect(Collectors.toList());
        GeneralF1Predictor generalF1Predictor = new GeneralF1Predictor();
        generalF1Predictor.setMaxSize(maxSize);
        return generalF1Predictor.predict(cbm.getNumClasses(), nonZeros);
    }

    private MultiLabel predictBySupport(Vector vector){
        double[] probs = cbm.predictAssignmentProbs(vector,support, piThreshold);
        GeneralF1Predictor generalF1Predictor = new GeneralF1Predictor();
        generalF1Predictor.setMaxSize(maxSize);
        return generalF1Predictor.predict(cbm.getNumClasses(),support,probs);
    }


//    public MultiLabel showPredictBySampling(Vector vector){
//        System.out.println("sampling procedure");
////        List<MultiLabel> samples = cbm.samples(vector, numSamples);
//        Pair<List<MultiLabel>, List<Double>> pair = cbm.samples(vector, probMassThreshold);
//        List<Pair<MultiLabel, Double>> list = new ArrayList<>();
//        List<MultiLabel> labels = pair.getFirst();
//        List<Double> probs = pair.getSecond();
//        for (int i=0;i<labels.size();i++){
//            list.add(new Pair<>(labels.get(i),probs.get(i)));
//        }
//        Comparator<Pair<MultiLabel, Double>> comparator = Comparator.comparing(a-> a.getSecond());
//
//        System.out.println(list.stream().sorted(comparator.reversed()).collect(Collectors.toList()));
//
//
//
////        for (int i=0;i<labels.size();i++){
////            System.out.println(labels.get(i)+": "+probs.get(i));
////        }
//        return GeneralF1Predictor.predict(cbm.getNumClasses(),pair.getFirst(), pair.getSecond());
//    }
//
//    public void showPredictBySamplingNonEmpty(Vector vector){
//        System.out.println("sampling procedure");
//        Pair<List<MultiLabel>, List<Double>> pair = cbm.sampleNonEmptySets(vector, probMassThreshold);
//        List<Pair<MultiLabel, Double>> list = new ArrayList<>();
//        List<MultiLabel> labels = pair.getFirst();
//        List<Double> probs = pair.getSecond();
//        double[] probsArray = probs.stream().mapToDouble(a->a).toArray();
//
//        for (int i=0;i<labels.size();i++){
//            list.add(new Pair<>(labels.get(i),probs.get(i)));
//        }
//        Comparator<Pair<MultiLabel, Double>> comparator = Comparator.comparing(a-> a.getSecond());
//
//        MultiLabel gfmPred =  GeneralF1Predictor.predict(cbm.getNumClasses(),pair.getFirst(), pair.getSecond());
//        MultiLabel argmaxPre = cbm.predict(vector);
//        System.out.println("expected f1 of argmax predictor= "+GeneralF1Predictor.expectedF1(labels,probsArray, argmaxPre,cbm.getNumClasses()));
//        System.out.println("expected f1 of GFM predictor= "+GeneralF1Predictor.expectedF1(labels,probsArray, gfmPred,cbm.getNumClasses()));
//
//        System.out.println(list.stream().sorted(comparator.reversed()).collect(Collectors.toList()));
//    }

    public GeneralF1Predictor.Analysis showPredictBySupport(Vector vector, MultiLabel truth){
//        System.out.println("support procedure");
        double[] probArray = cbm.predictAssignmentProbs(vector,support);
        GeneralF1Predictor generalF1Predictor = new GeneralF1Predictor();
        MultiLabel prediction =  generalF1Predictor.predict(cbm.getNumClasses(),support,probArray);
        GeneralF1Predictor.Analysis analysis = GeneralF1Predictor.showSupportPrediction(support,probArray, truth, prediction, cbm.getNumClasses());
        return analysis;
    }


    @Override
    public CBM getModel() {
        return cbm;
    }


}