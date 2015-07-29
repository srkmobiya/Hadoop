package face.recognition;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.Pointer;
import org.opencv.core.Mat;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_highgui.*;
import static org.bytedeco.javacpp.opencv_legacy.*;
import face.recognition.*;
/** Recognizes faces.
 *
 * @author reed
 */
public class FaceRecognition {

	/** the logger */
	private static final Logger LOGGER = Logger.getLogger(FaceRecognition.class.getName());
	/** the number of training faces */
	private int nTrainFaces = 0;
	/** the training face image array */
	IplImage[] trainingFaceImgArr;
	/** the test face image array */
	IplImage[] testFaceImgArr;
	/** the person number array */
	CvMat personNumTruthMat;
	/** the number of persons */
	int nPersons;
	/** the person names */
	final static List<String> personNames = new ArrayList<String>();
	/** the number of eigenvalues */
	int nEigens = 0;
	/** eigenvectors */
	IplImage[] eigenVectArr;
	/** eigenvalues */
	CvMat eigenValMat;
	/** the average image */
	IplImage pAvgTrainImg;
	/** the projected training faces */
	CvMat projectedTrainFaceMat;

	/** for query image files*/
	QueryObj[] queryArray;

	/** Constructs a new FaceRecognition instance. */
	public FaceRecognition() {
	}

	/** Trains from the data in the given training text index file, and store the trained data into the file 'facedata.xml'.
	 *
	 * @param trainingFileName the given training text index file
	 */
	public void learn(List<TrainData> listOfImages) {
		int i;

		// load training data
		//LOGGER.info("===========================================");
		//LOGGER.info("Loading the training images in " + trainingFileName);
		trainingFaceImgArr = loadFaceImgArray(listOfImages);
		nTrainFaces = trainingFaceImgArr.length;
		System.out.println("nTrainFaces = "+nTrainFaces);
		//LOGGER.info("Got " + nTrainFaces + " training images");
		if (nTrainFaces < 3) {
			LOGGER.severe("Need 3 or more training faces\n"
					+ "Input file contains only " + nTrainFaces);
			return;
		}

		// do Principal Component Analysis on the training faces
		doPCA();

		//LOGGER.info("projecting the training images onto the PCA subspace");
		// project the training images onto the PCA subspace
		projectedTrainFaceMat = cvCreateMat(
				nTrainFaces, // rows
				nEigens, // cols
				CV_32FC1); // type, 32-bit float, 1 channel

		// initialize the training face matrix - for ease of debugging
		for (int i1 = 0; i1 < nTrainFaces; i1++) {
			for (int j1 = 0; j1 < nEigens; j1++) {
				projectedTrainFaceMat.put(i1, j1, 0.0);
			}
		}

		//LOGGER.info("created projectedTrainFaceMat with " + nTrainFaces + " (nTrainFaces) rows and " + nEigens + " (nEigens) columns");
		if (nTrainFaces < 5) {
			//LOGGER.info("projectedTrainFaceMat contents:\n" + oneChannelCvMatToString(projectedTrainFaceMat));
		}

		final FloatPointer floatPointer = new FloatPointer(nEigens);
		for (i = 0; i < nTrainFaces; i++) {
			cvEigenDecomposite(
					trainingFaceImgArr[i], // face image
					nEigens, // nEigObjs
					eigenVectArr, // eigInput (Pointer)
					0, // ioFlags
					null, // userData (Pointer)
					pAvgTrainImg, // avg
					floatPointer); // coeffs (FloatPointer)

			if (nTrainFaces < 5) {
				//LOGGER.info("floatPointer: " + floatPointerToString(floatPointer));
			}
			for (int j1 = 0; j1 < nEigens; j1++) {
				projectedTrainFaceMat.put(i, j1, floatPointer.get(j1));
			}
		}
		if (nTrainFaces < 5) {
			//LOGGER.info("projectedTrainFaceMat after cvEigenDecomposite:\n" + projectedTrainFaceMat);
		}

		// store the recognition data as an xml file
		storeTrainingData();

		// Save all the eigenvectors as images, so that they can be checked.
		storeEigenfaceImages();
	}

	/** Recognizes the face in each of the test images given, and compares the results with the truth.
	 *
	 * @param szFileTest the index file of test images
	 */
	public void recognizeFileList(final String szFileTest) {
		//LOGGER.info("===========================================");
		//LOGGER.info("recognizing faces indexed from " + szFileTest);
		int i = 0;
		int nTestFaces = 0;         // the number of test images
		CvMat trainPersonNumMat;  // the person numbers during training
		float[] projectedTestFace;
		String answer;
		int nCorrect = 0;
		int nWrong = 0;
		double timeFaceRecognizeStart;
		double tallyFaceRecognizeTime;
		float confidence = 0.0f;

		// load test images and ground truth for person number
		//testFaceImgArr = loadFaceImgArray(szFileTest);
		nTestFaces = testFaceImgArr.length;

		//LOGGER.info(nTestFaces + " test faces loaded");

		// load the saved training data
		trainPersonNumMat = loadTrainingData();
		if (trainPersonNumMat == null) {
			return;
		}

		// project the test images onto the PCA subspace
		projectedTestFace = new float[nEigens];
		timeFaceRecognizeStart = (double) cvGetTickCount();        // Record the timing.

		for (i = 0; i < nTestFaces; i++) {
			int iNearest;
			int nearest;
			int truth;

			// project the test image onto the PCA subspace
			cvEigenDecomposite(
					testFaceImgArr[i], // obj
					nEigens, // nEigObjs
					eigenVectArr, // eigInput (Pointer)
					0, // ioFlags
					null, // userData
					pAvgTrainImg, // avg
					projectedTestFace);  // coeffs

			////LOGGER.info("projectedTestFace\n" + floatArrayToString(projectedTestFace));

			final FloatPointer pConfidence = new FloatPointer(confidence);
			iNearest = findNearestNeighbor(projectedTestFace, new FloatPointer(pConfidence));
			confidence = pConfidence.get();
			truth = personNumTruthMat.data_i().get(i);
			nearest = trainPersonNumMat.data_i().get(iNearest);

			if (nearest == truth) {
				answer = "Correct";
				nCorrect++;
			} else {
				answer = "WRONG!";
				nWrong++;
				LOGGER.info("nearest = " + nearest + ", Truth = " + truth + " (" + answer + "). Name = " + personNames.get(nearest));
			}
			//LOGGER.info("nearest = " + nearest + ", Truth = " + truth + " (" + answer + "). Name = " + personNames.get(nearest));
		}
		tallyFaceRecognizeTime = (double) cvGetTickCount() - timeFaceRecognizeStart;
		if (nCorrect + nWrong > 0) {
			LOGGER.info("TOTAL ACCURACY: " + (nCorrect * 100 / (nCorrect + nWrong)) + "% out of " + (nCorrect + nWrong) + " tests.");
			//LOGGER.info("TOTAL TIME: " + (tallyFaceRecognizeTime / (cvGetTickFrequency() * 1000.0 * (nCorrect + nWrong))) + " ms average.");
		}
	}

	/** Reads the names & image filenames of people from a text file, and loads all those images listed.
	 *
	 * @param filename the training file name
	 * @return the face image array
	 */
	private IplImage[] loadFaceImgArray(List<TrainData> listOfImages) {
		IplImage[] faceImgArr;
		int iFace = 0;
		int nFaces = 0;
		System.out.println("nfaces = "+listOfImages.size());

		nFaces=listOfImages.size();

		// allocate the face-image array and person number matrix
		faceImgArr = new IplImage[nFaces];
		//error
		personNumTruthMat = cvCreateMat(
				1, // rows
				nFaces, // cols
				CV_32SC1); // type, 32-bit unsigned, one channel

		// initialize the person number matrix - for ease of debugging
		for (int j1 = 0; j1 < nFaces; j1++) {
			personNumTruthMat.put(0, j1, 0);
		}

		int personNumber=0;
		String prevPersonName="";
		// store the face images in an array
		for (TrainData obj : listOfImages) {
			String personName=obj.name;

			if(prevPersonName.isEmpty()){
				personNames.add(personName);  
				prevPersonName=personName;
				
				++personNumber;
				nPersons = personNumber;
				LOGGER.info("Got new person " + personName + " -> nPersons = " + nPersons + " [" + personNames.size() + "]");
			}

			// Check if a new person is being loaded.
			if (prevPersonName.compareTo(personName)!=0) {
				// Allocate memory for the extra person (or possibly multiple), using this new person's name.
				personNames.add(personName);  
				++personNumber;
				nPersons = personNumber;
				prevPersonName=personName;
				LOGGER.info("Got new person " + personName + " -> nPersons = " + nPersons + " [" + personNames.size() + "]");
			}

			// Keep the data
			personNumTruthMat.put(
					0, // i
					iFace, // j
					personNumber); // v

			faceImgArr[iFace] = obj.faceImg;
			++iFace;
		}
		System.out.println("length of image is :"+faceImgArr.length);
		return faceImgArr;
	}

	/** Does the Principal Component Analysis, finding the average image and the eigenfaces that represent any image in the given dataset. */
	private void doPCA() {
		int i;
		CvTermCriteria calcLimit;
		CvSize faceImgSize = new CvSize();

		// set the number of eigenvalues to use
		nEigens = nTrainFaces - 1;

		//LOGGER.info("allocating images for principal component analysis, using " + nEigens + (nEigens == 1 ? " eigenvalue" : " eigenvalues"));

		// allocate the eigenvector images
		faceImgSize.width(trainingFaceImgArr[0].width());
		faceImgSize.height(trainingFaceImgArr[0].height());
		eigenVectArr = new IplImage[nEigens];
		for (i = 0; i < nEigens; i++) {
			eigenVectArr[i] = cvCreateImage(
					faceImgSize, // size
					IPL_DEPTH_32F, // depth
					1); // channels
		}

		// allocate the eigenvalue array
		eigenValMat = cvCreateMat(
				1, // rows
				nEigens, // cols
				CV_32FC1); // type, 32-bit float, 1 channel

		// allocate the averaged image
		pAvgTrainImg = cvCreateImage(
				faceImgSize, // size
				IPL_DEPTH_32F, // depth
				1); // channels

		// set the PCA termination criterion
		calcLimit = cvTermCriteria(
				CV_TERMCRIT_ITER, // type
				nEigens, // max_iter
				1); // epsilon

		//LOGGER.info("computing average image, eigenvalues and eigenvectors");
		// compute average image, eigenvalues, and eigenvectors
		cvCalcEigenObjects(
				nTrainFaces, // nObjects
				trainingFaceImgArr, // input
				eigenVectArr, // output
				CV_EIGOBJ_NO_CALLBACK, // ioFlags
				0, // ioBufSize
				null, // userData
				calcLimit,
				pAvgTrainImg, // avg
				eigenValMat.data_fl()); // eigVals

		//LOGGER.info("normalizing the eigenvectors");
		cvNormalize(
				eigenValMat, // src (CvArr)
				eigenValMat, // dst (CvArr)
				1, // a
				0, // b
				CV_L1, // norm_type
				null); // mask
	}

	/** Stores the training data to the file 'facedata.xml'. */
	private void storeTrainingData() {
		CvFileStorage fileStorage;
		int i;

		//LOGGER.info("writing facedata.xml");

		// create a file-storage interface
		fileStorage = cvOpenFileStorage(
				"facedata.xml", // filename
				null, // memstorage
				CV_STORAGE_WRITE, // flags
				null); // encoding

		// Store the person names. Added by Shervin.
		cvWriteInt(
				fileStorage, // fs
				"nPersons", // name
				nPersons); // value

		for (i = 0; i < nPersons; i++) {
			String varname = "personName_" + (i + 1);
			System.out.println(personNames.get(i));
			cvWriteString(
					fileStorage, // fs
					varname, // name
					personNames.get(i), // string
					0); // quote
		}

		// store all the data
		cvWriteInt(
				fileStorage, // fs
				"nEigens", // name
				nEigens); // value

		cvWriteInt(
				fileStorage, // fs
				"nTrainFaces", // name
				nTrainFaces); // value

		cvWrite(
				fileStorage, // fs
				"trainPersonNumMat", // name
				personNumTruthMat); // value

		cvWrite(
				fileStorage, // fs
				"eigenValMat", // name
				eigenValMat); // value

		cvWrite(
				fileStorage, // fs
				"projectedTrainFaceMat", // name
				projectedTrainFaceMat);

		cvWrite(fileStorage, // fs
				"avgTrainImg", // name
				pAvgTrainImg); // value

		for (i = 0; i < nEigens; i++) {
			String varname = "eigenVect_" + i;
			cvWrite(
					fileStorage, // fs
					varname, // name
					eigenVectArr[i]); // value
		}

		// release the file-storage interface
		cvReleaseFileStorage(fileStorage);
	}

	/** Opens the training data from the file 'facedata.xml'.
	 *
	 * @param pTrainPersonNumMat
	 * @return the person numbers during training, or null if not successful
	 */
	private CvMat loadTrainingData() {
		//LOGGER.info("loading training data");
		CvMat pTrainPersonNumMat = null; // the person numbers during training
		CvFileStorage fileStorage;
		int i;

		// create a file-storage interface
		fileStorage = cvOpenFileStorage(
				"facedata.xml", // filename
				null, // memstorage
				CV_STORAGE_READ, // flags
				null); // encoding
		if (fileStorage == null) {
			LOGGER.severe("Can't open training database file 'facedata.xml'.");
			return null;
		}

		// Load the person names.
		personNames.clear();        // Make sure it starts as empty.
		nPersons = cvReadIntByName(
				fileStorage, // fs
				null, // map
				"nPersons", // name
				0); // default_value
		if (nPersons == 0) {
			LOGGER.severe("No people found in the training database 'facedata.xml'.");
			return null;
		} else {
			//LOGGER.info(nPersons + " persons read from the training database");
		}

		// Load each person's name.
		for (i = 0; i < nPersons; i++) {
			String sPersonName;
			String varname = "personName_" + (i + 1);
			sPersonName = cvReadStringByName(
					fileStorage, // fs
					null, // map
					varname,
					"");
			personNames.add(sPersonName);
		}
		//LOGGER.info("person names: " + personNames);

		// Load the data
		nEigens = cvReadIntByName(
				fileStorage, // fs
				null, // map
				"nEigens",
				0); // default_value
		nTrainFaces = cvReadIntByName(
				fileStorage,
				null, // map
				"nTrainFaces",
				0); // default_value
		Pointer pointer = cvReadByName(
				fileStorage, // fs
				null, // map
				"trainPersonNumMat"); // name
		pTrainPersonNumMat = new CvMat(pointer);

		pointer = cvReadByName(
				fileStorage, // fs
				null, // map
				"eigenValMat"); // name
		eigenValMat = new CvMat(pointer);

		pointer = cvReadByName(
				fileStorage, // fs
				null, // map
				"projectedTrainFaceMat"); // name
		projectedTrainFaceMat = new CvMat(pointer);

		pointer = cvReadByName(
				fileStorage,
				null, // map
				"avgTrainImg");
		pAvgTrainImg = new IplImage(pointer);

		eigenVectArr = new IplImage[nTrainFaces];
		for (i = 0; i <= nEigens; i++) {
			String varname = "eigenVect_" + i;
			pointer = cvReadByName(
					fileStorage,
					null, // map
					varname);
			eigenVectArr[i] = new IplImage(pointer);
		}

		// release the file-storage interface
		cvReleaseFileStorage(fileStorage);

		//LOGGER.info("Training data loaded (" + nTrainFaces + " training images of " + nPersons + " people)");
		final StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append("People: ");
		if (nPersons > 0) {
			stringBuilder.append("<").append(personNames.get(0)).append(">");
		}
		for (i = 1; i < nPersons; i++) {
			stringBuilder.append(", <").append(personNames.get(i)).append(">");
		}
		//LOGGER.info(stringBuilder.toString());

		return pTrainPersonNumMat;
	}

	/** Saves all the eigenvectors as images, so that they can be checked. */
	private void storeEigenfaceImages() {
		// Store the average image to a file
		//LOGGER.info("Saving the image of the average face as 'out_averageImage.bmp'");
		cvSaveImage("out_averageImage.bmp", pAvgTrainImg);

		// Create a large image made of many eigenface images.
		// Must also convert each eigenface image to a normal 8-bit UCHAR image instead of a 32-bit float image.
		//LOGGER.info("Saving the " + nEigens + " eigenvector images as 'out_eigenfaces.bmp'");

		if (nEigens > 0) {
			// Put all the eigenfaces next to each other.
			int COLUMNS = 8;        // Put upto 8 images on a row.
			int nCols = Math.min(nEigens, COLUMNS);
			int nRows = 1 + (nEigens / COLUMNS);        // Put the rest on new rows.
			int w = eigenVectArr[0].width();
			int h = eigenVectArr[0].height();
			CvSize size = cvSize(nCols * w, nRows * h);
			final IplImage bigImg = cvCreateImage(
					size,
					IPL_DEPTH_8U, // depth, 8-bit Greyscale UCHAR image
					1);        // channels
			for (int i = 0; i < nEigens; i++) {
				// Get the eigenface image.
				IplImage byteImg = convertFloatImageToUcharImage(eigenVectArr[i]);
				// Paste it into the correct position.
				int x = w * (i % COLUMNS);
				int y = h * (i / COLUMNS);
				CvRect ROI = cvRect(x, y, w, h);
				cvSetImageROI(
						bigImg, // image
						ROI); // rect
				cvCopy(
						byteImg, // src
						bigImg, // dst
						null); // mask
				cvResetImageROI(bigImg);
				cvReleaseImage(byteImg);
			}
			cvSaveImage(
					"out_eigenfaces.bmp", // filename
					bigImg); // image
			cvReleaseImage(bigImg);
		}
	}

	/** Converts the given float image to an unsigned character image.
	 *
	 * @param srcImg the given float image
	 * @return the unsigned character image
	 */
	private IplImage convertFloatImageToUcharImage(IplImage srcImg) {
		IplImage dstImg;
		if ((srcImg != null) && (srcImg.width() > 0 && srcImg.height() > 0)) {
			// Spread the 32bit floating point pixels to fit within 8bit pixel range.
			double[] minVal = new double[1];
			double[] maxVal = new double[1];
			cvMinMaxLoc(srcImg, minVal, maxVal);
			// Deal with NaN and extreme values, since the DFT seems to give some NaN results.
			if (minVal[0] < -1e30) {
				minVal[0] = -1e30;
			}
			if (maxVal[0] > 1e30) {
				maxVal[0] = 1e30;
			}
			if (maxVal[0] - minVal[0] == 0.0f) {
				maxVal[0] = minVal[0] + 0.001;  // remove potential divide by zero errors.
			}                        // Convert the format
			dstImg = cvCreateImage(cvSize(srcImg.width(), srcImg.height()), 8, 1);
			cvConvertScale(srcImg, dstImg, 255.0 / (maxVal[0] - minVal[0]), -minVal[0] * 255.0 / (maxVal[0] - minVal[0]));
			return dstImg;
		}
		return null;
	}

	/** Find the most likely person based on a detection. Returns the index, and stores the confidence value into pConfidence.
	 *
	 * @param projectedTestFace the projected test face
	 * @param pConfidencePointer a pointer containing the confidence value
	 * @param iTestFace the test face index
	 * @return the index
	 */
	private int findNearestNeighbor(float projectedTestFace[], FloatPointer pConfidencePointer) {
		double leastDistSq = Double.MAX_VALUE;
		int i = 0;
		int iTrain = 0;
		int iNearest = 0;

		//LOGGER.info("................");
		//LOGGER.info("find nearest neighbor from " + nTrainFaces + " training faces");
		for (iTrain = 0; iTrain < nTrainFaces; iTrain++) {
			////LOGGER.info("considering training face " + (iTrain + 1));
			double distSq = 0;

			for (i = 0; i < nEigens; i++) {
				//LOGGER.debug("  projected test face distance from eigenface " + (i + 1) + " is " + projectedTestFace[i]);

				float projectedTrainFaceDistance = (float) projectedTrainFaceMat.get(iTrain, i);
				float d_i = projectedTestFace[i] - projectedTrainFaceDistance;
				distSq += d_i * d_i; // / eigenValMat.data_fl().get(i);  // Mahalanobis distance (might give better results than Eucalidean distance)
				//          if (iTrain < 5) {
				//            //LOGGER.info("    ** projected training face " + (iTrain + 1) + " distance from eigenface " + (i + 1) + " is " + projectedTrainFaceDistance);
				//            //LOGGER.info("    distance between them " + d_i);
				//            //LOGGER.info("    distance squared " + distSq);
				//          }
			}

			if (distSq < leastDistSq) {
				leastDistSq = distSq;
				iNearest = iTrain;
				//LOGGER.info("  training face " + (iTrain + 1) + " is the new best match, least squared distance: " + leastDistSq);
			}
		}

		// Return the confidence level based on the Euclidean distance,
		// so that similar images should give a confidence between 0.5 to 1.0,
		// and very different images should give a confidence between 0.0 to 0.5.
		float pConfidence = (float) (1.0f - Math.sqrt(leastDistSq / (float) (nTrainFaces * nEigens)) / 255.0f);
		pConfidencePointer.put(pConfidence);

		//LOGGER.info("training face " + (iNearest + 1) + " is the final best match, confidence " + pConfidence);
		return iNearest;
	}

	/** Returns a string representation of the given float array.
	 *
	 * @param floatArray the given float array
	 * @return a string representation of the given float array
	 */
	private String floatArrayToString(final float[] floatArray) {
		final StringBuilder stringBuilder = new StringBuilder();
		boolean isFirst = true;
		stringBuilder.append('[');
		for (int i = 0; i < floatArray.length; i++) {
			if (isFirst) {
				isFirst = false;
			} else {
				stringBuilder.append(", ");
			}
			stringBuilder.append(floatArray[i]);
		}
		stringBuilder.append(']');

		return stringBuilder.toString();
	}

	/** Returns a string representation of the given float pointer.
	 *
	 * @param floatPointer the given float pointer
	 * @return a string representation of the given float pointer
	 */
	private String floatPointerToString(final FloatPointer floatPointer) {
		final StringBuilder stringBuilder = new StringBuilder();
		boolean isFirst = true;
		stringBuilder.append('[');
		for (int i = 0; i < floatPointer.capacity(); i++) {
			if (isFirst) {
				isFirst = false;
			} else {
				stringBuilder.append(", ");
			}
			stringBuilder.append(floatPointer.get(i));
		}
		stringBuilder.append(']');

		return stringBuilder.toString();
	}

	/** Returns a string representation of the given one-channel CvMat object.
	 *
	 * @param cvMat the given CvMat object
	 * @return a string representation of the given CvMat object
	 */
	public String oneChannelCvMatToString(final CvMat cvMat) {
		//Preconditions
		if (cvMat.channels() != 1) {
			throw new RuntimeException("illegal argument - CvMat must have one channel");
		}

		final int type = cvMat.type();
		StringBuilder s = new StringBuilder("[ ");
		for (int i = 0; i < cvMat.rows(); i++) {
			for (int j = 0; j < cvMat.cols(); j++) {
				if (type == CV_32FC1 || type == CV_32SC1) {
					s.append(cvMat.get(i, j));
				} else {
					throw new RuntimeException("illegal argument - CvMat must have one channel and type of float or signed integer");
				}
				if (j < cvMat.cols() - 1) {
					s.append(", ");
				}
			}
			if (i < cvMat.rows() - 1) {
				s.append("\n  ");
			}
		}
		s.append(" ]");
		return s.toString();
	}

	/** Predicting the face images.
	 * 
	 */
	public void predictFileList(final String queryFile) {
		System.out.println("Predicting faces indexed from " + queryFile);
		int nQueryFaces = 0;         // the number of test images
		CvMat trainPersonNumMat;  // the person numbers during training
		float[] projectedTestFace;
		float confidence = 0.0f;

		// load test images and ground truth for person number
		queryArray = loadQueryImageFiles(queryFile);
		nQueryFaces = queryArray.length;

		System.out.println(nQueryFaces + " query faces loaded");

		// load the saved training data
		System.out.println("Loading Train Data from facedata.xml file");
		trainPersonNumMat = loadTrainingData();
		if (trainPersonNumMat == null) {
			return;
		}

		// project the test images onto the PCA subspace
		projectedTestFace = new float[nEigens];

		for (int j = 0; j < queryArray.length; j++) {
			QueryObj obj = queryArray[j];
			int iNearest;
			int nearest;
			int truth;

			// project the test image onto the PCA subspace
			cvEigenDecomposite(
					obj.img, // obj
					nEigens, // nEigObjs
					eigenVectArr, // eigInput (Pointer)
					0, // ioFlags
					null, // userData
					pAvgTrainImg, // avg
					projectedTestFace);  // coeffs

			////LOGGER.info("projectedTestFace\n" + floatArrayToString(projectedTestFace));

			final FloatPointer pConfidence = new FloatPointer(confidence);
			iNearest = findNearestNeighbor(projectedTestFace, new FloatPointer(pConfidence));
			confidence = pConfidence.get();
			nearest = trainPersonNumMat.data_i().get(iNearest);
			obj.name=personNames.get(nearest-1);
			System.out.println("Prediction = " + obj.name +"\tImage path = "+obj.path);
		}
	}
	/** Reads the names & image filenames of people from a text file, and loads all those images listed.
	 *
	 * @param filename the training file name
	 * @return the face image array
	 */
	private QueryObj[] loadQueryImageFiles(final String filename) {
		QueryObj[] ObjArray;
		BufferedReader imgListFile;
		int iFace = 0;
		int nFaces = 0;
		try {
			// open the input file
			imgListFile = new BufferedReader(new FileReader(filename));

			// count the number of faces
			while (true) {
				String line = imgListFile.readLine();
				if (line == null || line.isEmpty()) {
					break;
				}
				nFaces++;
			}
			//LOGGER.info("nFaces: " + nFaces);
			imgListFile = new BufferedReader(new FileReader(filename));

			// allocate the face-image array and person number matrix
			ObjArray = new QueryObj[nFaces];

			// store the face images in an array
			for (iFace = 0; iFace < nFaces; iFace++) {

				// read person number (beginning with 1), their name and the image filename.
				final String line = imgListFile.readLine();
				if (line.isEmpty()) {
					break;
				}
				QueryObj obj=new QueryObj();
				obj.path=line;
				obj.name="";

				// load the face image
				obj.img = cvLoadImage(
						obj.path, // filename
						CV_LOAD_IMAGE_GRAYSCALE); // isColor
				ObjArray[iFace]=obj;
				if (ObjArray[iFace].img == null) {
					throw new RuntimeException("Can't load image from " + ObjArray[iFace].path);
				}
			}

			imgListFile.close();

		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}

		System.out.println("Data loaded from '" + filename + "': (" + nFaces + " images of " + nPersons + " people).");

		return ObjArray;
	}
}
