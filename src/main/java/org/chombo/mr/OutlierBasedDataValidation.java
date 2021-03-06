/*
 * chombo: Hadoop Map Reduce utility
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.chombo.mr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.chombo.util.LongRunningStats;
import org.chombo.util.SecondarySort;
import org.chombo.util.Tuple;
import org.chombo.util.Utility;

/**
 * Std deviation based based outlier detection for multiple quant field
 * @author pranab
 *
 */
public class OutlierBasedDataValidation extends Configured implements Tool {
	@Override
	public int run(String[] args) throws Exception {
        Job job = new Job(getConf());
        String jobName = "Detecting invalid data as outliers";
        job.setJobName(jobName);
        
        job.setJarByClass(OutlierBasedDataValidation.class);

        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        Utility.setConfiguration(job.getConfiguration(), "chombo");
        job.setMapperClass(OutlierBasedDataValidation.DataValidatorMapper.class);
        job.setReducerClass(OutlierBasedDataValidation.DataValidatorReducer.class);
        
        job.setMapOutputKeyClass(Tuple.class);
        job.setMapOutputValueClass(Tuple.class);

        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);

        job.setGroupingComparatorClass(SecondarySort.TuplePairGroupComprator.class);
        job.setPartitionerClass(SecondarySort.TuplePairPartitioner.class);

        int numReducer = job.getConfiguration().getInt("obd.num.reducer", -1);
        numReducer = -1 == numReducer ? job.getConfiguration().getInt("num.reducer", 1) : numReducer;
        job.setNumReduceTasks(numReducer);

        int status =  job.waitForCompletion(true) ? 0 : 1;
        return status;
	}

	/**
	 * @author pranab
	 *
	 */
	public static class DataValidatorMapper extends Mapper<LongWritable, Text, Tuple, Tuple> {
		private Tuple outKey = new Tuple();
		private Tuple outVal = new Tuple();
        private String fieldDelimRegex;
        private String[] items;
        private int[] quantityAttrOrdinals;
        private boolean isAggrFileSplit;
        private int[] idFieldOrdinals;
        private int statOrd;
        private static final int PER_FIELD_STAT_VAR_COUNT = 6;
        
        protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration config = context.getConfiguration();
        	fieldDelimRegex = config.get("field.delim.regex", ",");
        	quantityAttrOrdinals = Utility.intArrayFromString(config.get("quantity.attr.ordinals"));
        	
        	String incrFilePrefix = config.get("incremental.file.prefix", "");
        	if (!incrFilePrefix.isEmpty()) {
        		isAggrFileSplit = !((FileSplit)context.getInputSplit()).getPath().getName().startsWith(incrFilePrefix);
        	} else {
        		throw new IOException("incremental file prefix needs to be specified");
        	}
        	
        	if (null != config.get("id.field.ordinals")) {
        		idFieldOrdinals = Utility.intArrayFromString(config.get("id.field.ordinals"));
        	}
       }
 
        @Override
        protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
            items  =  value.toString().split(fieldDelimRegex);
        	outKey.initialize();
        	outVal.initialize();
        	if (null != idFieldOrdinals) {
      			for (int ord : idFieldOrdinals ) {
      				outKey.append(items[ord]);
      			}
        	} else {
        		//all fields before quantity are ID fields
	            for (int i = 0; i < quantityAttrOrdinals[0];  ++i) {
	            	outKey.append(items[i]);
	            }
        	}
        	
        	if (isAggrFileSplit) {
        		outKey.append(0);
        		
        		//stat fields start after last quant field
    			statOrd = quantityAttrOrdinals[quantityAttrOrdinals.length-1] + 1;
    			
    			for ( int ord : quantityAttrOrdinals) {
        			//existing aggregation - quantity attrubute ordinal, avg, std dev
                    outVal.add(0, Integer.parseInt(items[statOrd]), Long.parseLong(items[statOrd+4]) ,  
                    		Double.parseDouble(items[statOrd + 5]));
                    statOrd += PER_FIELD_STAT_VAR_COUNT;
    			}
        	} else {
        		//incremental - whole record
        		outKey.append(1);
        		for (String item : items) {
        			outVal.add(item);
        		}
        	}
        	context.write(outKey, outVal);
        }
 	}	

	   /**
	  *	 @author pranab
	  *
	 */
	public static class  DataValidatorReducer extends Reducer<Tuple, Tuple, NullWritable, Text> {
		private Text outVal = new Text();
		private  String fieldDelim;
		private int ord;
		private long avg;
		private double stdDev;
		private int[] quantityAttrOrdinals;
		private int index;
		private int recType;
		private String[] record;
		private float stdDevMult;
	    private Map<Integer, LongRunningStats> runningStats = new HashMap<Integer, LongRunningStats>();
	    private long min;
	    private long max;
	    private long delta;
	    private boolean valid;
	    private long fieldValue;
	    private String outputType;
	    private boolean toOutput;
	    private String stVal;
	    private List<Integer> invalidFields = new ArrayList<Integer>();
	    private LongRunningStats stat;
	       		
		/* (non-Javadoc)
		 * @see org.apache.hadoop.mapreduce.Reducer#setup(org.apache.hadoop.mapreduce.Reducer.Context)
		 */
		protected void setup(Context context) throws IOException, InterruptedException {
			Configuration config = context.getConfiguration();
			fieldDelim = config.get("field.delim.out", ",");
			quantityAttrOrdinals = Utility.intArrayFromString(config.get("quantity.attr.ordinals"));
			stdDevMult = config.getFloat("std.dev.mult", (float)3.0);
			outputType = config.get("output.type", "invalid");
		}
		
 	/* (non-Javadoc)
 	 * @see org.apache.hadoop.mapreduce.Reducer#reduce(KEYIN, java.lang.Iterable, org.apache.hadoop.mapreduce.Reducer.Context)
 	 */
		protected void reduce(Tuple key, Iterable<Tuple> values, Context context)
     	throws IOException, InterruptedException {
			record = null;
			runningStats.clear();
			invalidFields.clear();
			for (Tuple val : values) {
				index = 0;
				recType = val.getInt(index++);
 			
				//all quant fields
				if (recType == 0) {
					//aggregate with stats
					for ( int quantOrd : quantityAttrOrdinals) {
						ord = val.getInt(index++);
						avg = val.getLong(index++);
						stdDev  = val.getDouble(index++);
	    				runningStats.put(ord, new LongRunningStats(ord, avg, stdDev));
					} 
				} else {
					//record
					record = val. subTupleAsArray(1);
				}
			}
 		
			if (null != record) {
				valid = true;
				for ( int quantOrd : quantityAttrOrdinals) {
    				stat = runningStats.get(quantOrd);
    				delta = Math.round(stat.getStdDev() * stdDevMult);
    				min = stat.getAvg() -  delta;
    				max = stat.getAvg() +  delta;
    				fieldValue = Long.parseLong(record[quantOrd]);
    				valid = fieldValue >= min && fieldValue <= max;
    				if (!valid) {
    					invalidFields.add(quantOrd);
    				}
				}

				valid = invalidFields.isEmpty();
				if (!valid) {
					context.getCounter("Data quality", "invalid").increment(1);
				}
				toOutput = outputType.equals("valid") && valid || outputType.equals("invalid") && !valid ||
						outputType.equals("all");
				if (toOutput) {
					stVal = Utility.join(record);
					
					//append invalid field ordinals
					if (outputType.equals("all")) {
						stVal = stVal + fieldDelim + Utility.join(invalidFields, ":");
					}
					outVal.set(stVal);
					context.write(NullWritable.get(), outVal);
				}
			}
		}
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		int exitCode = ToolRunner.run(new OutlierBasedDataValidation(), args);
		System.exit(exitCode);
	}
	
}
