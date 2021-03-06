package input;

import java.io.File;
import java.util.ArrayList;
import java.util.StringTokenizer;

import btree.IntegerKey;
import btree.KeyClass;
import btree.StringKey;
import columnar.Columnarfile;
import diskmgr.PCounter;
import global.*;
import heap.Heapfile;
import heap.Scan;
import heap.Tuple;
import index.ColumnIndexScan;
import iterator.*;

public class Query {
	
	private String columnDBName;
	private String columnarFileName;
	private String targetColsStr;
	private String constraintStr;
	private String[] targetColNames;
	private String accessType;
	private Integer numBuf;
	private String columnName;
	private AttrType[] types;
	private Columnarfile columnarFile;
	private int outCount;
	private int[] outAttrTypes;
	private int[] out_indexes;

	public void execute(String[] args) {
		try {
			if (args.length < 6) {
				throw new Exception("Invalid number of attributes.");
			} else {
				columnDBName = args[0];
				if(!new File(columnDBName).exists()) {
					throw new Exception("Database does not exist.");
				}

				columnarFileName = args[1];

				targetColsStr = args[2];
				if(!(targetColsStr.startsWith("[") && targetColsStr.endsWith("]"))) {
					throw new Exception("[TARGETCOLUMNNAMES] format invalid.");
				} else {
					targetColNames = targetColsStr.substring(1,targetColsStr.length()-1).trim().split("\\s*,\\s*");
					if(targetColNames.length<1) {
						throw new Exception("No target columns given.");
					}
					outCount = (short) targetColNames.length;
				}

				constraintStr = args[3];
				if(!(constraintStr.startsWith("{") && constraintStr.endsWith("}"))) {
					throw new Exception("VALUECONSTRAINT format invalid.");
				}

				try {
					numBuf = Integer.parseInt(args[4]);
					if(numBuf<1) {
						throw new Exception("NUMBUF is not more than 1.");
					}
				} catch (Exception e) {
					throw new Exception("NUMBUF is not integer.");
				}

				accessType = args[5];
				if(!(accessType.equalsIgnoreCase("FILESCAN")
						|| accessType.equalsIgnoreCase("COLUMNSCAN")
						|| accessType.equalsIgnoreCase("BTREE")
						|| accessType.equalsIgnoreCase("BITMAP"))) {
					throw new Exception("access type invalid.");
				}

				SystemDefs columnDb = new SystemDefs(columnDBName, 0, numBuf, null);

				columnarFile = new Columnarfile(columnarFileName);

				PCounter.initialize();
				int startReadCount = PCounter.rcounter;
                int startWriteCount = PCounter.wcounter;

				if(accessType.equalsIgnoreCase("FILESCAN")) {
					executeFileScan();
				} else if(accessType.equalsIgnoreCase("COLUMNSCAN")) {
					executeColumnScan();
				} else if(accessType.equalsIgnoreCase("BTREE")) {
					executeBtreeScan();
				} else {
					executeBitmapScan();
				}

				//Flushing all written data to disk.
				try {
					SystemDefs.JavabaseBM.flushAllPages();
				} catch (Exception e) {
//						e.printStackTrace();
				}

				int endReadCount = PCounter.rcounter;
                int endWriteCount = PCounter.wcounter;
                System.out.println("Read Page Count: "+(endReadCount-startReadCount));
                System.out.println("Write Page Count: "+(endWriteCount-startWriteCount));
				System.out.println("Read Pages: "+PCounter.readPages);
				System.out.println("Wrote Pages: "+PCounter.writePages);
//				System.out.println("Pinned Pages: "+PCounter.currentlyPinnedPages);
				System.out.println("=======================EXTRA METAINFO===============================");
				System.out.println(columnarFile.getTupleCnt());
				columnarFile.printDeleteBitset();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void executeFileScan() throws Exception {
		FldSpec [] projectionList = new FldSpec[outCount];
		buildProjection(projectionList);
		CondExpr[] outFilter = new CondExpr[2];
		buildQueryCondExpr(outFilter);
		ColumnarFileScan fs = new ColumnarFileScan(columnarFileName, columnarFile.getAttributeTypes(),
				columnarFile.getStringSizes(), columnarFile.getFieldCount(), outCount,
				projectionList, outFilter);
		Tuple outputTuple=null;

		int resultCount = 0;
		for(int i=0; i<outCount; i++) {
			System.out.print(targetColNames[i]);
			if(i<outCount-1) System.out.print(", ");
		}
		System.out.println();
		while((outputTuple=fs.get_next())!=null){
			for(int i=0; i<outCount; i++) {
				if(outAttrTypes[i]==AttrType.attrInteger) {
					System.out.print(outputTuple.getIntFld(i+1));
				} else {
					System.out.print(outputTuple.getStrFld(i+1));
				}
				if(i<outCount-1) System.out.print(", ");
			}
			System.out.println();
			resultCount++;
		}
		fs.close();
		System.out.println();
		System.out.println("************************************************************************");
		System.out.println("Total Results Count By Query: "+resultCount);
		System.out.println("************************************************************************");
		System.out.println();
	}

	public void executeColumnScan() throws Exception {
		FldSpec [] projectionList = new FldSpec[outCount];
		buildProjection(projectionList);
		CondExpr[] outFilter = new CondExpr[2];
		buildQueryCondExprColscan(outFilter);
		String consAttr[] = constraintStr.substring(1,constraintStr.length()-1).trim().split(",");
		int colIndex = columnarFile.colNameToIndex(consAttr[0]);
		ColumnarColumnScan colScan = new ColumnarColumnScan(columnarFile,
				colIndex,
				outCount,
				out_indexes,
				projectionList,
				outFilter);

		Tuple outputTuple=null;
		int resultCount = 0;
		for(int i=0; i<outCount; i++) {
			System.out.print(targetColNames[i]);
			if(i<outCount-1) System.out.print(", ");
		}
		System.out.println();
		while((outputTuple=colScan.get_next())!=null){
			for(int i=0; i<outCount; i++) {
				if(outAttrTypes[i]==AttrType.attrInteger) {
					System.out.print(outputTuple.getIntFld(i+1));
				} else {
					System.out.print(outputTuple.getStrFld(i+1));
				}
				if(i<outCount-1) System.out.print(", ");
			}
			System.out.println();
			resultCount++;
		}
		colScan.close();
		System.out.println();
		System.out.println("************************************************************************");
		System.out.println("Total Results Count By Query: "+resultCount);
		System.out.println("************************************************************************");
		System.out.println();
	}

	public void executeBtreeScan() throws Exception {
		columnarFile = new Columnarfile(columnarFileName);
		FldSpec [] projectionList = new FldSpec[outCount];
		buildProjection(projectionList);
		CondExpr[] outFilter = new CondExpr[2];
		buildQueryCondExprColscan(outFilter);
		String consAttr[] = constraintStr.substring(1,constraintStr.length()-1).trim().split(",");
		int colIndex = columnarFile.colNameToIndex(consAttr[0]);

		if(columnarFile.btreeIndexExists(colIndex)) {
			boolean index_only = false;
			if(outCount==1 && out_indexes[0]==colIndex) {
				index_only = true;
			}
			ColumnIndexScan indexScan = new ColumnIndexScan(new IndexType(IndexType.B_Index), columnarFile,
					columnarFile.get_fileName()+".btree." + colIndex,
					columnarFile.getAttributeTypes(),
					columnarFile.getStringSizes(),
					columnarFile.getFieldCount(), outCount, out_indexes,
					projectionList, outFilter, colIndex+1, index_only);
			Tuple outputTuple=null;
			int resultCount = 0;
			for(int i=0; i<outCount; i++) {
				System.out.print(targetColNames[i]);
				if(i<outCount-1) System.out.print(", ");
			}
			System.out.println();
			while((outputTuple=indexScan.get_next())!=null){
				for(int i=0; i<outCount; i++) {
					if(outAttrTypes[i]==AttrType.attrInteger) {
						System.out.print(outputTuple.getIntFld(i+1));
					} else {
						System.out.print(outputTuple.getStrFld(i+1));
					}
					if(i<outCount-1) System.out.print(", ");
				}
				System.out.println();
				resultCount++;
			}
			indexScan.close();
			System.out.println();
			System.out.println("************************************************************************");
			System.out.println("Total Results Count By Query: "+resultCount);
			System.out.println("************************************************************************");
			System.out.println();
		} else {
			throw new Exception("BTREE index does not exist on column "+consAttr[0]);
		}
	}

	public void executeBitmapScan() throws Exception {
		columnarFile = new Columnarfile(columnarFileName);
		FldSpec [] projectionList = new FldSpec[outCount];
		buildProjection(projectionList);
		CondExpr[] outFilter = new CondExpr[2];
		buildQueryCondExprColscan(outFilter);
		String consAttr[] = constraintStr.substring(1,constraintStr.length()-1).trim().split(",");
		int colIndex = columnarFile.colNameToIndex(consAttr[0]);

		if(columnarFile.bitmapIndexExists(colIndex)) {
			boolean index_only = false;
			if(outCount==1 && out_indexes[0]==colIndex && outFilter[0].op.attrOperator==AttrOperator.aopEQ) {
				index_only = true;
			}
			ColumnIndexScan indexScan = new ColumnIndexScan(new IndexType(IndexType.Bitmap), columnarFile,
					columnarFile.get_fileName()+".bm." + colIndex + "." + consAttr[2],
					columnarFile.getAttributeTypes(),
					columnarFile.getStringSizes(),
					columnarFile.getFieldCount(), outCount, out_indexes,
					projectionList, outFilter, colIndex+1, index_only);
			Tuple outputTuple=null;
			int resultCount = 0;
			for(int i=0; i<outCount; i++) {
				System.out.print(targetColNames[i]);
				if(i<outCount-1) System.out.print(", ");
			}
			System.out.println();
			System.out.println("hi");
			while((outputTuple=indexScan.get_next())!=null){
				for(int i=0; i<outCount; i++) {
					if(outAttrTypes[i]==AttrType.attrInteger) {
						System.out.print(outputTuple.getIntFld(i+1));
					} else {
						System.out.print(outputTuple.getStrFld(i+1));
					}
					if(i<outCount-1) System.out.print(", ");
				}
				System.out.println();
				resultCount++;
			}
			indexScan.close();
			System.out.println();
			System.out.println("************************************************************************");
			System.out.println("Total Results Count By Query: "+resultCount);
			System.out.println("************************************************************************");
			System.out.println();
		} else {
			throw new Exception("Bitmap index does not exist on column "+consAttr[0]);
		}
	}
	
	private void buildQueryCondExpr(CondExpr[] expr) throws Exception{
		String consAttr[] = constraintStr.substring(1,constraintStr.length()-1).trim().split(",");
		if(consAttr.length!=3) {
			throw new Exception("Invalid VALUECONSTRAINT elements");
		}
		int colIndex = columnarFile.colNameToIndex(consAttr[0]);
		int colOffset = colIndex+1;

		expr[0]=new CondExpr();
		expr[0].op = AttrOperator.findOperator(consAttr[1]);
		expr[0].next  = null;
	    expr[0].type1 = new AttrType(AttrType.attrSymbol);
	    if(columnarFile.getAttributeTypes()[colIndex].attrType == AttrType.attrInteger){
	    	expr[0].type2 = new AttrType(AttrType.attrInteger);
	    } else {
	    	expr[0].type2 = new AttrType(AttrType.attrString);
	    }
	    expr[0].operand1.symbol = new FldSpec (new RelSpec(RelSpec.outer), colOffset); //offset to be checked
	    if(expr[0].type2.attrType==AttrType.attrInteger){
	    	expr[0].operand2.integer=Integer.parseInt(consAttr[2]);
	    } else {
	    	expr[0].operand2.string=consAttr[2];
	    }
	    expr[1] = null;
	}
	
	private void buildProjection(FldSpec[] projection) throws Exception {
		out_indexes = new int[outCount];
		outAttrTypes = new int[outCount];
		for(int i=0;i<outCount;i++){
			int colIndex = columnarFile.colNameToIndex(targetColNames[i]);
			out_indexes[i] = colIndex;
			int colOffset = colIndex+1;
			projection[i]=new FldSpec(new RelSpec(RelSpec.outer), colOffset);
			outAttrTypes[i] = columnarFile.getAttributeTypes()[colIndex].attrType;
		}
	}

	private void buildQueryCondExprColscan(CondExpr[] expr) throws Exception{
		String consAttr[] = constraintStr.substring(1,constraintStr.length()-1).trim().split(",");
		if(consAttr.length!=3) {
			throw new Exception("Invalid VALUECONSTRAINT elements");
		}
		int colIndex = columnarFile.colNameToIndex(consAttr[0]);

		expr[0]=new CondExpr();
		expr[0].op = AttrOperator.findOperator(consAttr[1]);
		expr[0].next  = null;
		expr[0].type1 = new AttrType(AttrType.attrSymbol);
		if(columnarFile.getAttributeTypes()[colIndex].attrType == AttrType.attrInteger){
			expr[0].type2 = new AttrType(AttrType.attrInteger);
		} else {
			expr[0].type2 = new AttrType(AttrType.attrString);
		}
		expr[0].operand1.symbol = new FldSpec (new RelSpec(RelSpec.outer), 1); //offset to be checked
		if(expr[0].type2.attrType==AttrType.attrInteger){
			expr[0].operand2.integer=Integer.parseInt(consAttr[2]);
		} else {
			expr[0].operand2.string=consAttr[2];
		}
		expr[1] = null;
	}
}
