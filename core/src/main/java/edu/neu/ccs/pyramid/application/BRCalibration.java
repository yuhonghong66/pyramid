package edu.neu.ccs.pyramid.application;

import edu.neu.ccs.pyramid.calibration.*;

import edu.neu.ccs.pyramid.configuration.Config;
import edu.neu.ccs.pyramid.dataset.*;
import edu.neu.ccs.pyramid.eval.CalibrationEval;
import edu.neu.ccs.pyramid.eval.MLMeasures;
import edu.neu.ccs.pyramid.feature.FeatureList;
import edu.neu.ccs.pyramid.multilabel_classification.*;
import edu.neu.ccs.pyramid.multilabel_classification.br.SupportPredictor;
import edu.neu.ccs.pyramid.multilabel_classification.cbm.BMDistribution;
import edu.neu.ccs.pyramid.multilabel_classification.cbm.CBM;

import edu.neu.ccs.pyramid.multilabel_classification.predictor.IndependentPredictor;
import edu.neu.ccs.pyramid.util.Pair;
import edu.neu.ccs.pyramid.util.ParallelFileWriter;
import edu.neu.ccs.pyramid.util.ParallelStringMapper;
import edu.neu.ccs.pyramid.util.Serialization;
import org.apache.mahout.math.Vector;

import java.io.File;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class BRCalibration {
    public static void main(Config config) throws Exception{

        Logger logger = Logger.getAnonymousLogger();
        String logFile = config.getString("output.log");
        FileHandler fileHandler = null;
        if (!logFile.isEmpty()){
            new File(logFile).getParentFile().mkdirs();
            //todo should append?
            fileHandler = new FileHandler(logFile, true);
            java.util.logging.Formatter formatter = new SimpleFormatter();
            fileHandler.setFormatter(formatter);
            logger.addHandler(fileHandler);
            logger.setUseParentHandlers(false);
        }
        logger.info(config.toString());
        
        if (config.getBoolean("calibrate")){
            calibrate(config, logger);
        }

        if (config.getBoolean("test")){
            test(config, logger);
        }

        if (fileHandler!=null){
            fileHandler.close();
        }
    }
    public static void main(String[] args) throws Exception {
        Config config = new Config(args[0]);
        main(config);

    }


    private static void calibrate(Config config, Logger logger) throws Exception{


        logger.info("start training calibrator");
        MultiLabelClfDataSet train = TRECFormat.loadMultiLabelClfDataSet(config.getString("input.trainData"), DataSetType.ML_CLF_SEQ_SPARSE, true);
        //todo
        MultiLabelClfDataSet cal = TRECFormat.loadMultiLabelClfDataSet(config.getString("input.validData"), DataSetType.ML_CLF_SEQ_SPARSE, true);

        CBM cbm = (CBM) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model").toFile());


        LabelCalibrator labelCalibrator = null;
        switch (config.getString("labelCalibrator")){
            case "isotonic":
                labelCalibrator = new IsoLabelCalibrator(cbm, cal);
                break;
            case "none":
                labelCalibrator = new IdentityLabelCalibrator();
                break;
        }


        PredictionVectorizer predictionVectorizer = PredictionVectorizer.newBuilder()
                .brProb(config.getBoolean("brProb"))
                .setPrior(config.getBoolean("setPrior"))
                .cardPrior(config.getBoolean("cardPrior"))
                .card(config.getBoolean("card"))
                .pairPrior(config.getBoolean("pairPrior"))
                .encodeLabel(config.getBoolean("encodeLabel"))
                .f1Prior(config.getBoolean("f1Prior"))
                .cbmProb(config.getBoolean("cbmProb"))
                .implication(config.getBoolean("implication"))
                .labelProbs(config.getBoolean("labelProbs"))
                .position(config.getBoolean("position"))
                .logScale(config.getBoolean("logScale"))
                .build(train,labelCalibrator);




        RegDataSet calibratorTrainData = predictionVectorizer.createCaliTrainingData(cal,cbm,config.getInt("calibrator.train.numCandidates"));

        VectorCalibrator setCalibrator = null;

        switch (config.getString("setCalibrator")){
            case "cardinality_isotonic":
                setCalibrator = new VectorCardIsoSetCalibrator(calibratorTrainData, 1, 3);
                break;
            case "reranker":
                RerankerTrainer rerankerTrainer = RerankerTrainer.newBuilder()
                            .numCandidates(config.getInt("numCandidates"))
                            .monotonic(config.getBoolean("monotonic"))
                            .numIterations(config.getInt("numIterations"))
                            .numLeaves(config.getInt("numLeaves"))
                            .build();
                setCalibrator = rerankerTrainer.train(calibratorTrainData, cbm,predictionVectorizer);
                break;
            case "isotonic":
                setCalibrator = new VectorIsoSetCalibrator(calibratorTrainData,1);
                break;
            case "none":
                setCalibrator = new VectorIdentityCalibrator(1);
                break;
            default:
                throw new IllegalArgumentException("illegal setCalibrator");
        }



        Serialization.serialize(labelCalibrator,Paths.get(config.getString("output.dir"),"model_predictions","lr","models",
                "calibrators",config.getString("calibrate.folder"),"label_calibrator").toFile());
        Serialization.serialize(setCalibrator,Paths.get(config.getString("output.dir"),"model_predictions","lr","models",
                "calibrators",config.getString("calibrate.folder"),"set_calibrator").toFile());
        Serialization.serialize(predictionVectorizer,Paths.get(config.getString("output.dir"),"model_predictions","lr","models",
                "calibrators",config.getString("calibrate.folder"),"prediction_vectorizer").toFile());
        logger.info("finish training calibrator");


    }


    private static void test(Config config, Logger logger) throws Exception{
        MultiLabelClfDataSet test = TRECFormat.loadMultiLabelClfDataSet(config.getString("input.testData"), DataSetType.ML_CLF_SPARSE, true);
        CBM cbm = (CBM) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model").toFile());
        LabelCalibrator labelCalibrator = (LabelCalibrator) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions","lr","models",
                "calibrators",config.getString("calibrate.folder"),"label_calibrator").toFile());
        VectorCalibrator setCalibrator = (VectorCalibrator) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions","lr","models",
                "calibrators",config.getString("calibrate.folder"),"set_calibrator").toFile());
        PredictionVectorizer predictionVectorizer = (PredictionVectorizer) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions","lr","models",
                "calibrators",config.getString("calibrate.folder"),"prediction_vectorizer").toFile());

        List<MultiLabel> support = (List<MultiLabel>) Serialization.deserialize(Paths.get(config.getString("output.dir"),"model_predictions","lr","models","support").toFile());



        MultiLabelClassifier classifier = null;
        switch (config.getString("predict.mode")){
            case "independent":
                classifier = new IndependentPredictor(cbm,labelCalibrator);
                break;
            case "support":
                classifier = new edu.neu.ccs.pyramid.multilabel_classification.predictor.SupportPredictor(cbm, labelCalibrator, support);
                break;

            case "reranker":
                classifier = (Reranker)setCalibrator;
                break;

            default:
                throw new IllegalArgumentException("illegal predict.mode");
        }
        MultiLabel[] predictions = classifier.predict(test);

        logger.info("test performance");
        logger.info(new MLMeasures(test.getNumClasses(),test.getMultiLabels(), predictions).toString());

        if (true) {
            logger.info("calibration performance on test set");

            List<PredictionVectorizer.Instance> instances = IntStream.range(0, test.getNumDataPoints()).parallel()
                    .boxed().map(i -> predictionVectorizer.createInstance(cbm, test.getRow(i),predictions[i],test.getMultiLabels()[i]))
                    .collect(Collectors.toList());

            eval(instances, setCalibrator, logger);

        }


        boolean simpleCSV = true;
        if (simpleCSV){
            File testDataFile = new File(config.getString("input.testData"));
            File csv = Paths.get(config.getString("output.dir"),"reports_lr",testDataFile.getName()+"_report_calibrated","report.csv").toFile();
            csv.getParentFile().mkdirs();
            List<Integer> list = IntStream.range(0,test.getNumDataPoints()).boxed().collect(Collectors.toList());
            ParallelStringMapper<Integer> mapper = (list1, i) -> simplePredictionAnalysisCalibrated(config, cbm, labelCalibrator, setCalibrator,
                    test, i, support, implications, pairPriors, cardPrior, setPrior);
            ParallelFileWriter.mapToString(mapper,list, csv,100  );
        }


        boolean topSets = true;
        if (topSets){
            File testDataFile = new File(config.getString("input.testData"));
            File csv = Paths.get(config.getString("output.dir"),"reports_lr",testDataFile.getName()+"_report_calibrated","top_sets.csv").toFile();
            csv.getParentFile().mkdirs();
            List<Integer> list = IntStream.range(0,test.getNumDataPoints()).boxed().collect(Collectors.toList());
            ParallelStringMapper<Integer> mapper = (list1, i) -> topKSets(config, cbm, labelCalibrator, setCalibrator,
                    test, i, support, implications, pairPriors, cardPrior, setPrior);
            ParallelFileWriter.mapToString(mapper,list, csv,100  );
        }

    }


    public static String simplePredictionAnalysisCalibrated(Config config,
                                                             CBM cbm,
                                                             LabelCalibrator labelCalibrator,
                                                             VectorCalibrator setCalibrator,
                                                             MultiLabelClfDataSet dataSet,
                                                             int dataPointIndex,
                                                             List<MultiLabel> support,
                                                             List<Pair<Integer, Integer>> implications,
                                                             double[][][] pairPriors,
                                                             Map<Integer, Double> cardPriors,
                                                             Map<MultiLabel, Double> setPrior
    ){
        StringBuilder sb = new StringBuilder();
        MultiLabel trueLabels = dataSet.getMultiLabels()[dataPointIndex];
        String id = dataSet.getIdTranslator().toExtId(dataPointIndex);
        LabelTranslator labelTranslator = dataSet.getLabelTranslator();
        double[] classProbs = cbm.predictClassProbs(dataSet.getRow(dataPointIndex));
        double[] calibratedClassProbs = labelCalibrator.calibratedClassProbs(classProbs);

        MultiLabel predicted = SupportPredictor.predict(calibratedClassProbs,support);

        List<Integer> classes = new ArrayList<Integer>();
        for (int k = 0; k < dataSet.getNumClasses(); k++){
            if (dataSet.getMultiLabels()[dataPointIndex].matchClass(k)
                    ||predicted.matchClass(k)){
                classes.add(k);
            }
        }

        Comparator<Pair<Integer,Double>> comparator = Comparator.comparing(pair->pair.getSecond());
        List<Pair<Integer,Double>> list = classes.stream().map(l -> {
            if (l < cbm.getNumClasses()) {
                return new Pair<>(l, calibratedClassProbs[l]);
            } else {
                return new Pair<>(l, 0.0);
            }
        }).sorted(comparator.reversed()).collect(Collectors.toList());
        for (Pair<Integer,Double> pair: list){
            int label = pair.getFirst();
            double prob = pair.getSecond();
            int match = 0;
            if (trueLabels.matchClass(label)){
                match=1;
            }
            sb.append(id).append("\t").append(labelTranslator.toExtLabel(label)).append("\t")
                    .append("single").append("\t").append(prob)
                    .append("\t").append(match).append("\n");
        }

        Vector feature = feature(config,cbm.computeBM(dataSet.getRow(dataPointIndex),0.001),predicted,setPrior,
                cardPriors,calibratedClassProbs,pairPriors,implications,Optional.empty());
        double probability = setCalibrator.calibrate(feature);


        List<Integer> predictedList = predicted.getMatchedLabelsOrdered();
        sb.append(id).append("\t");
        for (int i=0;i<predictedList.size();i++){
            sb.append(labelTranslator.toExtLabel(predictedList.get(i)));
            if (i!=predictedList.size()-1){
                sb.append(",");
            }
        }
        sb.append("\t");
        int setMatch = 0;
        if (predicted.equals(trueLabels)){
            setMatch=1;
        }
        sb.append("set").append("\t").append(probability).append("\t").append(setMatch).append("\n");
        return sb.toString();
    }


    public static String topKSets(Config config,
                                CBM cbm,
                                LabelCalibrator labelCalibrator,
                                VectorCalibrator setCalibrator,
                                MultiLabelClfDataSet dataSet,
                                int dataPointIndex,
                                List<MultiLabel> support,
                                List<Pair<Integer, Integer>> implications,
                                double[][][] pairPriors,
                                Map<Integer, Double> cardPriors,
                                Map<MultiLabel, Double> setPrior){
        StringBuilder sb = new StringBuilder();
        String id = dataSet.getIdTranslator().toExtId(dataPointIndex);
        LabelTranslator labelTranslator = dataSet.getLabelTranslator();
        double[] classProbs = cbm.predictClassProbs(dataSet.getRow(dataPointIndex));
        double[] calibratedClassProbs = labelCalibrator.calibratedClassProbs(classProbs);
        List<Pair<MultiLabel,Double>> topK = SupportPredictor.topKSetsAndProbs(calibratedClassProbs,support,config.getInt("report.labelSetLimit"));


        for (Pair<MultiLabel,Double> pair: topK){
            MultiLabel set = pair.getFirst();
            double uncalibrated = pair.getSecond();
            Vector feature = feature(config,cbm.computeBM(dataSet.getRow(dataPointIndex),0.001),set,setPrior,
                    cardPriors,calibratedClassProbs,pairPriors,implications,Optional.empty());
            double probability = setCalibrator.calibrate(feature);
            List<Integer> predictedList = set.getMatchedLabelsOrdered();
            sb.append(id).append("\t");
            for (int i=0;i<predictedList.size();i++){
                sb.append(labelTranslator.toExtLabel(predictedList.get(i)));
                if (i!=predictedList.size()-1){
                    sb.append(",");
                }
            }
            sb.append("\t");
            sb.append(probability);
            sb.append("\n");
        }

        return sb.toString();
    }


    private static CaliRes eval(List<Instance> predictions, VectorCalibrator calibrator, Logger logger){
        double mse = CalibrationEval.mse(generateStream(predictions,calibrator));
        double ace = CalibrationEval.absoluteError(generateStream(predictions,calibrator),10);
        double sharpness = CalibrationEval.sharpness(generateStream(predictions,calibrator),10);
        logger.info("mse="+mse);
        logger.info("absolute calibration error="+ace);
        logger.info("square calibration error="+CalibrationEval.squareError(generateStream(predictions,calibrator),10));
        logger.info("sharpness="+sharpness);
        logger.info("variance="+CalibrationEval.variance(generateStream(predictions,calibrator)));
        logger.info(Displayer.displayCalibrationResult(generateStream(predictions,calibrator)));
        CaliRes caliRes = new CaliRes();
        caliRes.mse = mse;
        caliRes.ace= ace;
        caliRes.sharpness = sharpness;
        return caliRes;
    }



    private static Stream<Pair<Double,Integer>> generateStream(List<Instance> predictions, VectorCalibrator vectorCalibrator){
        return predictions.stream()
                .parallel().map(pred->new Pair<>(vectorCalibrator.calibrate(pred.vector),(int)pred.correctness));
    }










    private static Instance predictedBySupport(Config config, MultiLabelClfDataSet dataSet, int index, Map<MultiLabel,Double> setPriors,
                                               Map<Integer,Double> cardPriors, CBM cbm, LabelCalibrator labelCalibrator,
                                               double[][][] pairPriors, List<Pair<Integer,Integer>> implications, List<MultiLabel> support){

        double[] marginals = labelCalibrator.calibratedClassProbs(cbm.predictClassProbs(dataSet.getRow(index)));
        MultiLabel prediction = SupportPredictor.predict(marginals, support);
        BMDistribution bmDistribution = cbm.computeBM(dataSet.getRow(index),0.001);
        return createInstance(config, bmDistribution,prediction,dataSet.getMultiLabels()[index],setPriors,cardPriors,marginals, pairPriors, implications, Optional.empty());
    }








    public static class CaliRes implements Serializable {
        public static final long serialVersionUID = 446782166720638575L;
        public double mse;
        public double ace;
        public double sharpness;
    }


    public static class BRSupportPrecictor implements MultiLabelClassifier{
        CBM cbm;
        List<MultiLabel> support;
        LabelCalibrator labelCalibrator;

        public BRSupportPrecictor(CBM cbm, List<MultiLabel> support, LabelCalibrator labelCalibrator) {
            this.cbm = cbm;
            this.support = support;
            this.labelCalibrator = labelCalibrator;
        }

        @Override
        public int getNumClasses() {
            return cbm.getNumClasses();
        }

        @Override
        public MultiLabel predict(Vector vector) {
            double[] marginals = cbm.predictClassProbs(vector);
            double[] calibratedMarginals = labelCalibrator.calibratedClassProbs(marginals);
            return SupportPredictor.predict(calibratedMarginals,support);
        }

        @Override
        public FeatureList getFeatureList() {
            return null;
        }

        @Override
        public LabelTranslator getLabelTranslator() {
            return null;
        }
    }

}
