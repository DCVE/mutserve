package genepi.vcbox.align;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import genepi.hadoop.common.WorkflowContext;
import genepi.vcbox.align.paired.PairedAlignerJob;
import genepi.vcbox.align.single.SingleAlignerJob;
import genepi.vcbox.util.HadoopJobStep;

public class AlignTool extends HadoopJobStep {

	HashMap<String, List<String>> mapPairs = new HashMap<String, List<String>>();
	protected static final Log log = LogFactory.getLog(AlignTool.class);

	enum DATA_TYPE {

		PAIRED_FASTQ, SINGLE_FASTQ, BAM

	}

	@Override
	public boolean run(WorkflowContext context) {

		boolean successful = false;
		String input = context.get("input");
		String inType = context.get("inType");
		String output = context.get("bwaOut");
		String reference = context.get("reference");

		if (inType.equals("se")) {

			SingleAlignerJob job = new SingleAlignerJob(
					"Align SingleEnd");
			job.setReference(reference);
			job.setInput(input);
			// set output dir to a static subdirectory. necessary for next step
			// (same as in PE)
			job.setOutput(output + "/0");
			job.setFolder(getFolder(AlignTool.class));
			job.setJarByClass(AlignTool.class);
			successful = executeHadoopJob(job, context);

		}

		else if (inType.equals("pe")) {

			generatePEPairs(input);

			int round = 0;

			log.info("Found pairs: " + mapPairs.size());

			/** iterate over all found pairs and execute MapReduce jobs */
			Set<String> keys = mapPairs.keySet();
			for (String key : keys) {
				List<String> values = mapPairs.get(key);

				if (values.size() == 2) {

					log.info("executing alignment for key " + key + " ["
							+ round + "]");

					String roundOutput = output + "/" + round;
					PairedAlignerJob job = new PairedAlignerJob(
							"Align PairedEnd:" + round);
					job.setInput(values.get(0), values.get(1));
					job.setOutput(roundOutput);
					job.setReference(reference);
					job.setFolder(getFolder(AlignTool.class));
					job.setJarByClass(AlignTool.class);
					successful = executeHadoopJob(job, context);

					/** for several files */
					round++;

				} else {
					successful = false;
					log.error("Pair is missing");
				}

			}
		}

		return successful;

	}

	private void generatePEPairs(String input) {
		try {
			FileSystem fileSystem;
			fileSystem = FileSystem.get(new Configuration());
			FileStatus[] fileList = fileSystem.listStatus(new Path(input));

			for (FileStatus file : fileList) {

				FSDataInputStream in = fileSystem.open(file.getPath());
				
				BufferedReader reader;
				
				if (file.getPath().getName().endsWith(".gz")) {
					GZIPInputStream gzis = new GZIPInputStream(in);
					InputStreamReader stream = new InputStreamReader(gzis);
					reader = new BufferedReader(stream);
				} else {
					reader = new BufferedReader(new InputStreamReader(in));
				}
				
				String line = reader.readLine();
				String[] keySplits = line.split(" ");
				String key;

				// old ILUMINA format
				if (line.charAt(line.length() - 2) == '/')
					key = keySplits[1].substring(0, keySplits[1].length() - 2);

				else
					// new ILUMINAformat (see Mapper!)
					key = keySplits[0];

				List<String> values = mapPairs.get(key);

				if (values == null) {
					values = new ArrayList<String>();
				}

				values.add(file.getPath().toString());
				mapPairs.put(key, values);
				reader.close();

			}
		}

		catch (IOException e) { // TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
