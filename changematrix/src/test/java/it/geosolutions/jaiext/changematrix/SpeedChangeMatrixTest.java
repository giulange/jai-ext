package it.geosolutions.jaiext.changematrix;

import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAX_THREADS_PER_BLOCK;
import static jcuda.driver.JCudaDriver.cuCtxCreate;
import static jcuda.driver.JCudaDriver.cuCtxDestroy;
import static jcuda.driver.JCudaDriver.cuCtxSynchronize;
import static jcuda.driver.JCudaDriver.cuDeviceGet;
import static jcuda.driver.JCudaDriver.cuDeviceGetAttribute;
import static jcuda.driver.JCudaDriver.cuDeviceGetCount;
import static jcuda.driver.JCudaDriver.cuInit;
import static jcuda.driver.JCudaDriver.cuLaunchKernel;
import static jcuda.driver.JCudaDriver.cuMemAlloc;
import static jcuda.driver.JCudaDriver.cuMemFree;
import static jcuda.driver.JCudaDriver.cuMemcpyDtoH;
import static jcuda.driver.JCudaDriver.cuMemcpyHtoD;
import static jcuda.driver.JCudaDriver.cuModuleGetFunction;
import static jcuda.driver.JCudaDriver.cuModuleLoad;
import static jcuda.driver.JCudaDriver.cuModuleUnload;
import it.geosolutions.jaiext.changematrix.ChangeMatrixDescriptor.ChangeMatrix;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.media.jai.ImageLayout;
import javax.media.jai.JAI;
import javax.media.jai.ParameterBlockJAI;
import javax.media.jai.RenderedOp;
import javax.media.jai.TileCache;
import javax.media.jai.TiledImage;

import jcuda.Pointer;
import jcuda.Sizeof;
import jcuda.driver.CUcontext;
import jcuda.driver.CUdevice;
import jcuda.driver.CUdeviceptr;
import jcuda.driver.CUfunction;
import jcuda.driver.CUmodule;
import jcuda.driver.JCudaDriver;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import com.carrotsearch.junitbenchmarks.AbstractBenchmark;
import com.carrotsearch.junitbenchmarks.BenchmarkOptions;
import com.sun.media.imageioimpl.common.ImageUtil;

/**
 * This test-class is used for testing images in the directory
 * src/test/resources/it/geosolutions/jaiext/changematrix/test-data-expanded. If
 * the data are not present, the test are not performed and a WARNING is shown,
 * or the user can change the source image directory to test-data. After the
 * addition of the images, the user can change some parameters:
 * DEFAULT_THREAD_NUMBER,which indicates the number of thread retrieving the
 * result image-tiles; DEFAULT_TILE_HEIGHT, the height of every tile of the 2
 * initial images; DEFAULT_TILE_WIDTH, the width of every tile of the 2 initial
 * images; NUM_CYCLES_BENCH, the number of the benchmark cycles for every test;
 * NUM_CYCLES_WARM, the number of the initial cycles for every test that are not
 * considered inside the statistics. The initial method startUp is used for reading
 * the 2 images, expanding the tileCache, fill the tile cache with the tile of both
 * images and calculation of the Histogram of the images for the changeMatrix. The first
 * test calculates the changematrix from the source and reference images and only the result
 * image tiles are removed from the cache. The second test simply adds the tiles of the 2 
 * images to the cache and then remove them from it, continuously.
 */
public class SpeedChangeMatrixTest extends AbstractBenchmark {

	//private final static String REFERENCE_PATH_FOR_TESTS = "d:/data/unina";
	private final static String REFERENCE_PATH_FOR_TESTS = "/home/giuliano/work/Projects/LIFE_Project/LUC_gpgpu/rasterize";

	private final static int DEFAULT_THREAD_NUMBER = 24;
    /* TileDim must be set according to the effective ROI passed to Cuda kernel.
     * The "effective" ROI size can change according to ROI subsetting due to addressed 
     * resources (e.g. number of GPUs, amount of RAM).
     * 
     * We need a dedicated Java Class carrying out this optimization task.
     * For now I assume that the whole image is processed at once.
     * 
     * But I cannot do that because Java should compute the ROI_new whose size can be divided
     * by TileDim[X,Y] with modulus=0.
     * This way I use a TileDim = [12750,9954] ready for that.
     */
	private final static int DEFAULT_TILE_HEIGHT 	= 12928;//12750;	//original:12888;
	private final static int DEFAULT_TILE_WIDTH 	= 10112;//9954;		//original:10008;

	private final static SetupClass initializationSetup = new SetupClass();

	// The first NUM_CYCLES_WARM cycles are not considered because they simply
	// allows the
	// Java Hotspot to compile the code. Then the other NUM_CYCLES_BENCH cycles
	// are calculated
	private static final int NUM_CYCLES_BENCH = 50;
	private static final int NUM_CYCLES_WARM = 20;


    // executor service for scheduling the computation threads.
    static ExecutorService ex = Executors.newFixedThreadPool(DEFAULT_THREAD_NUMBER);

	// Create the PTX file by calling the NVCC
	private final static String PTX_FILE_NAME = "/home/giuliano/work/Projects/LIFE_Project/LUC_gpgpu/gpgpu/changemat.ptx";

	private static double sum=0;
	private static int i=0;
	
	@Before
	public void init(){
		System.out.println("Begin Cycle: "+i++);		
	}
	
	@After
	public void after(){
		System.out.println("End Cycle: "+i);
	}
	
	@AfterClass
	public static void end(){
		System.out.println("Average time : "+(sum/i)/1E6);
		ex.shutdown();
	}

	@BeforeClass
	public static void startUp() {
		// Source and reference images acquisition
		final File file0 = new File(REFERENCE_PATH_FOR_TESTS,
				"clc2000_L3_100m.tif");
		final File file6 = new File(REFERENCE_PATH_FOR_TESTS,
				"clc2006_L3_100m.tif");

		if(!file0.exists()||!file0.canRead()||!file6.exists()||!file6.canRead()){
		    throw new IllegalArgumentException("Input files are not present!");
		}

                int tileH = DEFAULT_TILE_HEIGHT;
                int tileW = DEFAULT_TILE_WIDTH;
        
                // Tile dimension settings
                ImageLayout layout = new ImageLayout();
                layout.setTileHeight(tileH).setTileWidth(tileW);
                RenderingHints hints = new RenderingHints(JAI.KEY_IMAGE_LAYOUT, layout);
        
                // TileCache used by JAI
                TileCache defaultTileCache = JAI.getDefaultInstance().getTileCache();
        
                // Setting of the tile cache memory capacity 800M
                defaultTileCache.setMemoryCapacity(800 * 1024 * 1024);
                defaultTileCache.setMemoryThreshold(1.0f);
                JAI.getDefaultInstance().setTileCache(defaultTileCache);
                JAI.getDefaultInstance().getTileScheduler().setParallelism(24);
                // new TCTool((SunTileCache)defaultTileCache);
        
                // Forcing the cache to store the source and reference tiles
        
                final ParameterBlockJAI pbj = new ParameterBlockJAI("ImageRead");
                pbj.setParameter("Input", file0);
        
                RenderedOp source = JAI.create("ImageRead", pbj, hints);
        
                pbj.setParameter("Input", file6);
                RenderedOp reference = JAI.create("ImageRead", pbj, hints);
        
                // Cache tile filling
                source.getTiles();
                reference.getTiles();
        
        
                initializationSetup.setRenderingHints(hints);
                initializationSetup.setSource(source);
                initializationSetup.setReference(reference);
        
	}

	@Test
	@Ignore
	@BenchmarkOptions(benchmarkRounds = NUM_CYCLES_BENCH, warmupRounds = NUM_CYCLES_WARM)
	public void testJAI() throws InterruptedException {
		
		long init=System.nanoTime();
            RenderedOp reference = (RenderedOp) initializationSetup.getReference();
            RenderedOp source = (RenderedOp) initializationSetup.getSource();
    
            RenderingHints hints = initializationSetup.getRenderingHints();
    
            // ChangeMatrix creation
            final Set<Integer> classes = new HashSet<Integer>();
            for(int i=0;i<45;i++){
                classes.add(i);
            }
    
            final ChangeMatrix cm = new ChangeMatrix(classes);
    
            // ParameterBlock creation
            final ParameterBlockJAI pbj = new ParameterBlockJAI("ChangeMatrix");
            pbj.addSource(reference);
            pbj.addSource(source);
            pbj.setParameter("result", cm);
            final RenderedOp result = JAI.create("ChangeMatrix", pbj, hints);
            result.getWidth();
    
            // force computation
            final Queue<Point> tiles = new ArrayBlockingQueue<Point>(result.getNumXTiles()* result.getNumYTiles());
            for (int i = 0; i < result.getNumXTiles(); i++) {
                for (int j = 0; j < result.getNumYTiles(); j++) {
                    tiles.add(new Point(i, j));
                }
            }
            // Cycle for calculating all the changeMatrix statistics
            final CountDownLatch sem = new CountDownLatch(result.getNumXTiles() * result.getNumYTiles());
            for (final Point tile : tiles) {
                ex.execute(new Runnable() {
    
                    @Override
                    public void run() {
                        result.getTile(tile.x, tile.y);
                        sem.countDown();
                    }
                });
            }
            sem.await();
            cm.freeze(); // stop changing the computations! If we did not do
                         // this new
                         // values would be accumulated as the file was
                         // written
    
            sum+=(System.nanoTime()-init);
            
            System.out.println((System.nanoTime()-init)/1E6);
            
            // cache flushing
            JAI.getDefaultInstance().getTileCache().removeTiles(reference);
            JAI.getDefaultInstance().getTileCache().removeTiles(source);
            result.dispose();
	}

	@Test
//	@Ignore
	@BenchmarkOptions(benchmarkRounds = NUM_CYCLES_BENCH, warmupRounds = NUM_CYCLES_WARM)
	public void testCUDA() throws Exception {
		
		long init=System.nanoTime();
        RenderedOp reference = (RenderedOp) initializationSetup.getReference();
        RenderedOp source = (RenderedOp) initializationSetup.getSource();
            
	    // prepare tiles layout for input images
	    final int numTileX=reference.getNumXTiles();
	    final int numTileY=reference.getNumYTiles();
	    final int minTileX=reference.getMinTileX();
	    final int minTileY=reference.getMinTileY();
	    System.out.println("dimensions: "+reference.getBounds());
	    System.out.println("tileW: "+reference.getTileWidth()+" tileH: "+reference.getTileHeight());
	    
	    
	    //System.out.println(numTileX*numTileY);
	    final CountDownLatch sem = new CountDownLatch(numTileX * numTileY);
	    // cycle on tiles to call the CUDA code
	    for(int i=minTileY;i<minTileY+numTileY;i++){
	        for(int j=minTileX;j<minTileX+numTileX;j++){
	            ex.execute(new CUDAWorker(j, i, sem,reference,source));
	        }
	    }
	    sem.await(10,TimeUnit.MINUTES);
	    
        sum+=(System.nanoTime()-init);
        
        System.out.println((System.nanoTime()-init)/1E6);
        
        // cache flushing
        JAI.getDefaultInstance().getTileCache().removeTiles(reference);
        JAI.getDefaultInstance().getTileCache().removeTiles(source);
	}

	/**
	 * Creates an image from a vector of int
	 * @param rect
	 * @param data
	 * @return
	 */
	private TiledImage createImage(Rectangle rect, final int[] data) {
	    final SampleModel sm= new PixelInterleavedSampleModel(
	            DataBuffer.TYPE_INT,
	            rect.width,
	            rect.height,
	            1,
	            rect.width,
	            new int[]{0});
	    final DataBufferInt db1= new DataBufferInt(data, rect.width*rect.height);
	    final WritableRaster wr= 
	        com.sun.media.jai.codecimpl.util.RasterFactory.createWritableRaster(sm, db1, new Point(0,0));
	    
	    final TiledImage image= new TiledImage(
	            0,
	            0,
	            rect.width,
	            rect.height,
	            0,
	            0,
	            sm,
	            ImageUtil.createColorModel(sm)
	            );
	    image.setData(wr);// SG SHOULD BE OTIMIZED!
	    return image;
	}

	/**
	 * Stub method to be replaced with CUDA code
	 * @param host_iMap1 the reference data
	 * @param host_iMap2 the current data
	 * @param crossdim 
	 * @return a list of byte arrays containing the results
	 */
	private List<int[]> JCudaChangeMat(byte[] host_iMap1,byte[] host_iMap2, int crossdim)
	{
	    /*
	     * Copyright 2013 Massimo Nicolazzo & Giuliano Langella:
	     * 		(1) correct
	     * 		(2) compile
	     */
	    
	    /**
	     * This uses the JCuda driver bindings to load and execute two 
	     * CUDA kernels:
	     * (1)
	     * The first kernel executes the change matrix computation for
	     * the whole ROI given as input in the form of iMap1 & iMap2.
	     * It returns a 3D change matrix in which every 2D array 
	     * corresponds to a given CUDA-tile (not GIS-tile). 
	     * (2)
	     * The second kernel sum up the 3D change matrix returning one
	     * 2D array being the accountancy for the whole ROI. 
	     */
	
		// Enable exceptions and omit all subsequent error checks
	    JCudaDriver.setExceptionsEnabled(true);
	    
	    // ----
	    // opt function for different SIZEs
	    int tiledimX	= 128;
	    int tiledimY	= 128;
	    int ntilesX 	= DEFAULT_TILE_WIDTH  / tiledimX;
	    int ntilesY		= DEFAULT_TILE_HEIGHT / tiledimY;
	    // ----
	    
	    //System.out.println("ntilesX: "+ntilesX+"\tntilesY: "+ntilesY);
	    int mapsize 	= tiledimX * tiledimY * ntilesX * ntilesY *  Sizeof.INT;//Integer.SIZE;
	    int mapsizeb 	= tiledimX * tiledimY * ntilesX * ntilesY *  Sizeof.BYTE;//Byte.SIZE;
	    //System.out.println("mapsize: "+mapsize+"\tmapsizeb: "+mapsizeb);
	    
	    // Initialise the driver:
	    cuInit(0);
	    
	    // Obtain the number of devices:
	    int gpuDeviceCount[] = {0};
	    cuDeviceGetCount( gpuDeviceCount );
	    int deviceCount = gpuDeviceCount[0];
		if (deviceCount == 0) {
			System.out.println("error: no devices supporting CUDA.");
		    //exit();
		}
	    //System.out.println("Found " + deviceCount + " devices");
	    
	    // Select first device, but I should select/split devices
	    int selDev = 0;
	    CUdevice device = new CUdevice();
	    cuDeviceGet(device, selDev);
	    
	    // Get some useful properties: 
	    int amountProperty[] = { 0 };
	    // -1-
	    cuDeviceGetAttribute(amountProperty, CU_DEVICE_ATTRIBUTE_MAX_THREADS_PER_BLOCK, device);
	    int maxThreadsPerBlock = amountProperty[0];
	    //System.out.println("maxThreadsPerBlock: "+maxThreadsPerBlock);
	    // -2-
	    //cuDeviceGetAttribute(amountProperty, CU_DEVICE_ATTRIBUTE_TOTAL_CONSTANT_MEMORY, device);
	    //int totalGlobalMem = amountProperty[0];
	    //System.out.println("totalGlobalMem [to be corrected!!]: "+totalGlobalMem);
	    
	    // Create a context for the selected device
	    CUcontext context = new CUcontext();
	    //int cuCtxCreate_STATUS = 
	    cuCtxCreate(context, selDev, device);
	    //System.out.println("cuCtxCreate_STATUS: "+cuCtxCreate_STATUS);
	
	    // Load the ptx file.
	    //System.out.println("Loading ptx FILE...");
	    CUmodule module = new CUmodule();
	    //int cuModLoad = 
	    cuModuleLoad(module, PTX_FILE_NAME);
	    //System.out.println("cuModLoad: "+cuModLoad);
	    
	    // Obtain a function pointer to the "add" function.
	    //System.out.println("changemap MOD");
	    CUfunction changemap = new CUfunction();
	    cuModuleGetFunction(changemap, module, "_Z9changemapPKhS0_jjjjjPjS1_");
	    //System.out.println("changemat MOD");
	    CUfunction changemat = new CUfunction();
	    cuModuleGetFunction(changemat, module, "_Z9changematPjjj");
	
	    // Allocate the device input data, and copy the
	    // host input data to the device
	    //System.out.println("dev_iMap1");
	    CUdeviceptr dev_iMap1 = new CUdeviceptr();
	    cuMemAlloc(dev_iMap1, mapsizeb );
	    cuMemcpyHtoD(dev_iMap1, Pointer.to(host_iMap1), mapsizeb);
	    //System.out.println("dev_iMap2");
	    CUdeviceptr dev_iMap2 = new CUdeviceptr();
	    cuMemAlloc(dev_iMap2, mapsizeb );
	    cuMemcpyHtoD(dev_iMap2, Pointer.to(host_iMap2), mapsizeb);
	
	    // Allocate device output memory
	    //System.out.println("dev_oMap");
	    CUdeviceptr dev_oMap = new CUdeviceptr();
	    cuMemAlloc(dev_oMap, mapsize);
	    //System.out.println("dev_chMat");
	    CUdeviceptr dev_chMat = new CUdeviceptr();
	    cuMemAlloc(dev_chMat, crossdim * crossdim * ntilesX * ntilesY*Integer.SIZE);
	    
	
	    // System.out.println("first kernel");
	    // Set up the kernel parameters: A pointer to an array
	    // of pointers which point to the actual values.
	    Pointer kernelParameters1 = Pointer.to(
	        Pointer.to(dev_iMap1),
	        Pointer.to(dev_iMap2),
	        Pointer.to(new int[]{tiledimX}),
	        Pointer.to(new int[]{tiledimY}),
	        Pointer.to(new int[]{ntilesX}),
	        Pointer.to(new int[]{ntilesY}),
	        Pointer.to(new int[]{crossdim}),
	        Pointer.to(dev_chMat),
	        Pointer.to(dev_oMap)
	    );
	
	    //System.out.println("pointers done");
	    // Call the kernel function.
	    int blockSizeX 	= (int) Math.floor(Math.sqrt(maxThreadsPerBlock));									// 32
	    int blockSizeY 	= (int) Math.floor(Math.sqrt(maxThreadsPerBlock));									// 32
	    int blockSizeZ 	= 1;
	    int gridSizeX 	= 1+ (int) Math.ceil( Math.sqrt( (ntilesX*ntilesY) / (blockSizeX*blockSizeY) ) ); 	// 1+ 4 
	    int gridSizeY 	= 1+ (int) Math.ceil( Math.sqrt( (ntilesX*ntilesY) / (blockSizeX*blockSizeY) ) ); 	// 1+ 4
	    int gridSizeZ 	= 1;
	    //System.out.println("launch cuda kernel");
	    //int status_k1 = 
		cuLaunchKernel(changemap,
			gridSizeX,  gridSizeY, gridSizeZ,   	// Grid dimension
			blockSizeX, blockSizeY, blockSizeZ,     // Block dimension
	        0, null,               					// Shared memory size and stream
	        kernelParameters1, null 				// Kernel- and extra parameters
	    );
	    //System.out.println("	status k1 = "+status_k1);
	    //System.out.println("	dev_chMat.len = "+dev_chMat.getByteBuffer(0, 4));
	    //System.out.println("synchro");
	    //int status_syn1 = 
		cuCtxSynchronize();
	    //System.out.println("	synchro_1 = "+status_syn1);
	
	    //System.out.println("second kernel");
	    // Set up the kernel parameters: A pointer to an array
	    // of pointers which point to the actual values.
	    Pointer kernelParameters2 = Pointer.to(
	        Pointer.to(dev_chMat),
	        Pointer.to(new int[]{crossdim * crossdim}),
	        Pointer.to(new int[]{ntilesX * ntilesY})
	        );
	    //System.out.println("pointers done");
	    //System.out.println("launch cuda kernel");
	    //int status_k2 = 
		cuLaunchKernel(changemat,
			gridSizeX,  gridSizeY, gridSizeZ,   	// Grid dimension
			blockSizeX, blockSizeY, blockSizeZ,     // Block dimension
	        0, null,               					// Shared memory size and stream
	        kernelParameters2, null 				// Kernel- and extra parameters
	    );
	    //System.out.println("	status k2 = "+status_k2);
	    //int status_syn2 = 
		cuCtxSynchronize();
	    //System.out.println("	synchro_2 = "+status_syn2);
	
	    // Allocate host output memory and copy the device output
	    // to the host.
	    int host_chMat[] = new int[crossdim * crossdim];
	    //int cuMemcpy_oMap_STATUS = 
		cuMemcpyDtoH(Pointer.to(host_chMat), dev_chMat, crossdim * crossdim * Sizeof.INT);
	    //System.out.println("cuMemcpy_oMap_STATUS: "+cuMemcpy_oMap_STATUS);
	    int host_oMap[] = new int[tiledimX * tiledimY * ntilesX * ntilesY];
	    //System.out.println("mapsize: "+mapsize);
	    //int cuMemcpy_chMat_STATUS = 
	    cuMemcpyDtoH(Pointer.to(host_oMap), dev_oMap, mapsize);
	    //System.out.println("cuMemcpy_chMat_STATUS: "+cuMemcpy_chMat_STATUS);
	    
	    // Clean up.
	    cuMemFree(dev_iMap1);
	    cuMemFree(dev_iMap2);
	    cuMemFree(dev_oMap);
	    cuMemFree(dev_chMat);
	    
	    //
	    //int cuModUnload_STATUS = 
	    cuModuleUnload ( module );
	    //System.out.println("cuModUnload_STATUS: "+cuModUnload_STATUS);
	    
	    // Destroy  CUDA context:
	    //int cuDestroy_STATUS = 
	    cuCtxDestroy( context );
	    //System.out.println("cuDestroy_STATUS: "+cuDestroy_STATUS);
	    
	    // OUTPUT:
	    return Arrays.asList(host_oMap,host_chMat);
	}

	/** 
	 * This class is a JavaBean used for storing the image information and then passed them to the 2 test-method
	 * The stored information are: the source and reference image, the renderingHints related to the 2 images and 
	 * the histograms of the 2 images.
	 */
	private static class SetupClass {

		private RenderingHints hints;

		private RenderedImage source;

		private RenderedImage reference;

		public RenderingHints getRenderingHints() {
			return hints;
		}

		public void setRenderingHints(RenderingHints hints) {
			this.hints = hints;
		}

		public RenderedImage getSource() {
			return source;
		}

		public void setSource(RenderedImage source) {
			this.source = source;
		}

		public RenderedImage getReference() {
			return reference;
		}

		public void setReference(RenderedImage reference) {
			this.reference = reference;
		}

		SetupClass() {
		}
	}

	private final class CUDAWorker implements Runnable {
	    	
	    	/**
	    	 * Number of classes in the original data.
	    	 */
	        private static final int NUM_CLASSES = 44;

			private static final boolean WRITE = false;
	
			private final int tileX;
	        
	        private final int tileY;
	        
	        private final CountDownLatch latch;

			private RenderedOp reference;

			private RenderedOp current;
	
	        /**
	         * @param tileX
	         * @param tileY
	         * @param latch
	         */
	        public CUDAWorker(int tileX, int tileY, CountDownLatch latch, RenderedOp reference, RenderedOp current) {
	            this.tileX = tileX;
	            this.tileY = tileY;
	            this.latch = latch;
	            this.reference=reference;
	            this.current=current;
	        }
	     
	        @Override
	        public void run() {
	            
	            // get the raster for reference and current
	            Raster ref=reference.getTile(tileX, tileY),cur=current.getTile(tileX, tileY);
	            Rectangle rect= ref.getBounds();
	//            rect=rect.intersection(reference.getBounds()); // this has been disabled to NOT cut the provided tiles
//	            ref=reference.getData(rect);
//	            cur=current.getData(rect);
	            
	            // transform into byte array
	            final DataBufferByte dbRef = (DataBufferByte) ref.getDataBuffer();
	            final DataBufferByte dbCurrent = (DataBufferByte) cur.getDataBuffer();
	            byte dataRef[]=dbRef.getData(0);
	            byte dataCurrent[]=dbCurrent.getData(0);
	            
	            assert dataRef.length==rect.width*rect.height;
	            assert dataCurrent.length==rect.width*rect.height;
	            
	            System.out.println("Calling JCUDA tileX:"+tileX+" tileY:"+tileY);//+" bbox:"+rect.toString());
	            //System.out.println("Calling JCUDA tileW:"+rect.width+" tileH:"+rect.height);
	            // call CUDA and get result
	            // I am expecting the host_oMap as the first array and host_chMat as the second array
	            final List<int[]> result=JCudaChangeMat(dataRef,dataCurrent,NUM_CLASSES);
	            //System.out.println("Cuda kernels work fine !!");
	            
	            if(WRITE){

		            
		            // build output image and save
		            //System.out.println("Building output images:");
		            //System.out.println("	oMap:\t"+result.get(0).length);
		            final TiledImage image0 = createImage(rect, result.get(0));
		            //System.out.println("	chMat:\t"+result.get(1).length);
		            final Rectangle chMatDimensions= new Rectangle(0,0,NUM_CLASSES,NUM_CLASSES);
		            final TiledImage image1 = createImage(chMatDimensions, result.get(1));
		            
		            //System.out.println("Saving output images.\n\n");
		            try {
		                //ImageIO.write(biImage, "tiff", new File("d:/data/unina/test/row"+tileY+"_col"+tileX+"_"+".tif"));
		            	ImageIO.write(image0, "tiff", new File("/home/giuliano/git/jai-ext/out/0row"+tileY+"_col"+tileX+"_"+".tif"));
		            	ImageIO.write(image1, "tiff", new File("/home/giuliano/git/jai-ext/out/1row"+tileY+"_col"+tileX+"_"+".tif"));
		            } catch (IOException e) {
		                throw new RuntimeException(e);
		            }
		            image0.dispose();
		            image1.dispose();
	            }
	            latch.countDown();
	        }
	    }

}
