/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") +  you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.openmeetings.core.converter;

import static org.apache.openmeetings.util.OmFileHelper.EXTENSION_FLV;
import static org.apache.openmeetings.util.OmFileHelper.getRecordingMetaData;
import static org.apache.openmeetings.util.OmFileHelper.getStreamsHibernateDir;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.openmeetings.db.dao.record.RecordingDao;
import org.apache.openmeetings.db.dao.record.RecordingMetaDataDao;
import org.apache.openmeetings.db.entity.record.Recording;
import org.apache.openmeetings.db.entity.record.RecordingMetaData;
import org.apache.openmeetings.util.OmFileHelper;
import org.apache.openmeetings.util.process.ProcessHelper;
import org.apache.openmeetings.util.process.ProcessResult;
import org.apache.openmeetings.util.process.ProcessResultList;
import org.apache.wicket.util.string.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class InterviewConverter extends BaseConverter implements IRecordingConverter {
	private static final Logger log = LoggerFactory.getLogger(InterviewConverter.class);
	private static class ReConverterParams {
		private int leftSideLoud = 1;
		private int rightSideLoud = 1;
		@SuppressWarnings("unused")
		private Integer leftSideTime = 0;
		@SuppressWarnings("unused")
		private Integer rightSideTime = 0;
	}

	// Spring loaded Beans
	@Autowired
	private RecordingDao recordingDao;
	@Autowired
	private RecordingMetaDataDao metaDataDao;

	private String[] mergeAudioToWaves(List<File> waveFiles, File wav,
			List<RecordingMetaData> metaDataList, ReConverterParams rcv) throws IOException {
		String[] cmdSox = new String[waveFiles.size() + 5];
		cmdSox[0] = this.getPathToSoX();
		cmdSox[1] = "-m";

		int counter = 2;
		for (File _wav : waveFiles) {
			for (RecordingMetaData metaData : metaDataList) {
				String hashFileFullNameStored = metaData.getFullWavAudioData();

				if (hashFileFullNameStored.equals(_wav.getName())) {
					if (metaData.getInteriewPodId() == 1) {
						cmdSox[counter] = "-v " + rcv.leftSideLoud;
						counter++;
					}
					if (metaData.getInteriewPodId() == 2) {
						cmdSox[counter] = "-v " + rcv.rightSideLoud;
						counter++;
					}
				}
			}
			cmdSox[counter] = _wav.getCanonicalPath();
			counter++;
		}

		cmdSox[counter] = wav.getCanonicalPath();

		return cmdSox;
	}

	@Override
	public void startConversion(Long recordingId) {
		startConversion(recordingId, false, new ReConverterParams());
	}

	public void startConversion(Long id, boolean reconversion, ReConverterParams rcv) {
		Recording r = null;
		try {
			r = recordingDao.get(id);
			log.debug("recording {}", r.getId());
			if (Strings.isEmpty(r.getHash())) {
				r.setHash(UUID.randomUUID().toString());
			}
			r.setStatus(Recording.Status.CONVERTING);
			r = recordingDao.update(r);

			ProcessResultList logs = new ProcessResultList();
			List<File> waveFiles = new ArrayList<>();
			File streamFolder = getStreamFolder(r);
			List<RecordingMetaData> metaDataList = metaDataDao.getAudioMetaDataByRecording(r.getId());

			stripAudioFirstPass(r, logs, waveFiles, streamFolder, metaDataList);

			// Merge Wave to Full Length
			File streamFolderGeneral = getStreamsHibernateDir();

			File wav = new File(streamFolder, String.format("INTERVIEW_%s_FINAL_WAVE.wav", r.getId()));
			deleteFileIfExists(wav);

			if (waveFiles.isEmpty()) {
				// create default Audio to merge it.
				// strip to content length
				File outputWav = new File(streamFolderGeneral, "one_second.wav");

				// Calculate delta at beginning
				double deltaPadding = diffSeconds(r.getRecordEnd(), r.getRecordStart());

				String[] cmdSox = new String[] { getPathToSoX(), outputWav.getCanonicalPath(), wav.getCanonicalPath(), "pad", "0", String.valueOf(deltaPadding) };

				logs.add(ProcessHelper.executeScript("generateSampleAudio", cmdSox));
			} else if (waveFiles.size() == 1) {
				wav = waveFiles.get(0);
			} else {
				String[] soxArgs;
				if (reconversion) {
					soxArgs = mergeAudioToWaves(waveFiles, wav, metaDataList, rcv);
				} else {
					soxArgs = mergeAudioToWaves(waveFiles, wav);
				}

				logs.add(ProcessHelper.executeScript("mergeAudioToWaves", soxArgs));
			}
			// Default Image for empty interview video pods
			final File defaultInterviewImageFile = new File(streamFolderGeneral, "default_interview_image.png");

			if (!defaultInterviewImageFile.exists()) {
				throw new ConversionException("defaultInterviewImageFile does not exist!");
			}

			final int flvWidth = 320;
			final int flvHeight = 260;
			// Merge Audio with Video / Calculate resulting FLV

			String[] pods = new String[2];
			boolean found = false;
			for (RecordingMetaData meta : metaDataList) {
				File flv = getRecordingMetaData(r.getRoomId(), meta.getStreamName());

				Integer pod = meta.getInteriewPodId();
				if (flv.exists() && pod != null && pod > 0 && pod < 3) {
					String path = flv.getCanonicalPath();
					/*
					 * CHECK FILE:
					 * ffmpeg -i rec_316_stream_567_2013_08_28_11_51_45.flv -v error -f null file.null
					 */
					String[] args = new String[] {getPathToFFMPEG(), "-y"
							, "-i", path
							, "-an" // only input files with video will be treated as video sources
							, "-v", "error"
							, "-f", "null"
							, "file.null"};
					ProcessResult res = ProcessHelper.executeScript("checkFlvPod_" + pod , args, true);
					logs.add(res);
					if (res.isOk()) {
						long diff = diff(meta.getRecordStart(), meta.getRecording().getRecordStart());
						if (diff != 0L) {
							// stub to add
							// ffmpeg -y -loop 1 -i /home/solomax/work/openmeetings/branches/3.0.x/dist/red5/webapps/openmeetings/streams/hibernate/default_interview_image.jpg -filter_complex '[0:0]scale=320:260' -c:v libx264 -t 00:00:29.059 -pix_fmt yuv420p out.flv
							File podFB = new File(streamFolder, String.format("%s_pod_%s_blank.flv", meta.getStreamName(), pod));
							String podPB = podFB.getCanonicalPath();
							String[] argsPodB = new String[] { getPathToFFMPEG(), "-y" //
									, "-loop", "1", "-i", defaultInterviewImageFile.getCanonicalPath() //
									, "-filter_complex", String.format("[0:0]scale=%1$d:%2$d", flvWidth, flvHeight) //
									, "-c:v", "libx264" //
									, "-t", formatMillis(diff) //
									, "-pix_fmt", "yuv420p" //
									, podPB };
							logs.add(ProcessHelper.executeScript("blankFlvPod_" + pod , argsPodB));

							//ffmpeg -y -i out.flv -i rec_15_stream_4_2014_07_15_20_41_03.flv -filter_complex '[0:0]setsar=1/1[sarfix];[1:0]scale=320:260,setsar=1/1[scale];[sarfix] [scale] concat=n=2:v=1:a=0 [v]' -map '[v]'  output1.flv
							File podF = new File(streamFolder, OmFileHelper.getName(meta.getStreamName() + "_pod_" + pod, EXTENSION_FLV));
							String podP = podF.getCanonicalPath();
							String[] argsPod = new String[] { getPathToFFMPEG(), "-y"//
									, "-i", podPB //
									, "-i", path //
									, "-filter_complex", String.format("[0:0]setsar=1/1[sarfix];[1:0]scale=%1$d:%2$d,setsar=1/1[scale];[sarfix] [scale] concat=n=2:v=1:a=0 [v]", flvWidth, flvHeight) //
									, "-map", "[v]" //
									, podP };
							logs.add(ProcessHelper.executeScript("shiftedFlvPod_" + pod , argsPod));

							pods[pod - 1] = podP;
						} else {
							pods[pod - 1] = path;
						}
					}
					found = true;
				}
			}
			if (!found) {
				ProcessResult res = new ProcessResult();
				res.setProcess("CheckFlvFilesExists");
				res.setError("No valid pods found");
				res.setExitCode(-1);
				logs.add(res);
				return;
			}
			boolean shortest = false;
			List<String> args = new ArrayList<>();
			for (int i = 0; i < 2; ++i) {
				/*
				 * INSERT BLANK INSTEAD OF BAD PAD:
				 * ffmpeg -loop 1 -i default_interview_image.jpg -i rec_316_stream_569_2013_08_28_11_51_45.flv -filter_complex '[0:v]scale=320:260,pad=2*320:260[left];[1:v]scale=320:260[right];[left][right]overlay=main_w/2:0' -shortest -y out4.flv
				 *
				 * JUST MERGE:
				 * ffmpeg -i rec_316_stream_569_2013_08_28_11_51_45.flv -i rec_316_stream_569_2013_08_28_11_51_45.flv -filter_complex '[0:v]scale=320:260,pad=2*320:260[left];[1:v]scale=320:260[right];[left][right]overlay=main_w/2:0' -y out4.flv
				 */
				if (pods[i] == null) {
					shortest = true;
					args.add("-loop"); args.add("1");
					args.add("-i"); args.add(defaultInterviewImageFile.getCanonicalPath());
				} else {
					args.add("-i"); args.add(pods[i]);
				}
			}
			args.add("-i"); args.add(wav.getCanonicalPath());
			args.add("-filter_complex");
			args.add(String.format("[0:v]scale=%1$d:%2$d,pad=2*%1$d:%2$d[left];[1:v]scale=%1$d:%2$d[right];[left][right]overlay=main_w/2:0%3$s"
					, flvWidth, flvHeight, shortest ? ":shortest=1" : ""));
			if (shortest) {
				args.add("-shortest");
			}
			args.add("-map"); args.add("0:0");
			args.add("-map"); args.add("1:0");
			args.add("-map"); args.add("2:0");
			args.add("-qmax"); args.add("1");
			args.add("-qmin"); args.add("1");

			r.setWidth(2 * flvWidth);
			r.setHeight(flvHeight);

			String mp4path = convertToMp4(r, args, logs);

			postProcess(r, mp4path, logs, waveFiles);
		} catch (Exception err) {
			log.error("[startConversion]", err);
			r.setStatus(Recording.Status.ERROR);
		}
		recordingDao.update(r);
	}
}
