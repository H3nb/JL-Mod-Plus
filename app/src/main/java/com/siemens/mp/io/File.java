/*
 * Modified for JL-Mod Plus.
 *  Copyright 2018 Nikita Shakarun
 *  Copyright 2021 Yury Kharchenko
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.siemens.mp.io;

import android.annotation.SuppressLint;
import android.util.SparseArray;

import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

import org.microemu.cldc.file.VirtualFileSystem;
import javax.microedition.shell.MidletPermissionManager;

import io.github.h3nb.jlmodplus.util.FileUtils;

public class File {
	public static final int INSIDE_STORAGE_PATH = 1;
	public static final int OUTSIDE_STORAGE_PATH = 0;
	public static final String STORAGE_DRIVE = "a:";

	private static final SparseArray<RandomAccessFile> OPENED_FILES = new SparseArray<>();
	private static int lastDescriptor;

	public static String buildPath(String fileName) {
		// FIXME: 12.07.2021
		return fileName;
	}

	public static int checkFileName(String fileName) {
		return fileName.indexOf(':') == -1 ? INSIDE_STORAGE_PATH : OUTSIDE_STORAGE_PATH;
	}

	public int close(int fileDescriptor) throws IOException {
		RandomAccessFile file = OPENED_FILES.get(fileDescriptor);
		if (file != null) {
			OPENED_FILES.delete(fileDescriptor);
			file.close();
			return fileDescriptor;
		}
		return -1;
	}

	public static int copy(String source, String dest) throws IOException {
		FileUtils.copyFileUsingChannel(getFile(source), getFile(dest, 2));
		return 1;
	}

	public static int debugWrite(String fileName, String infoString) throws IOException {
		try (FileWriter writer = new FileWriter(getFile(fileName, 2), true)) {
			writer.write(infoString);
		}
		return 1;
	}

	public static int delete(String fileName) {
		return getFile(fileName, 2).delete() ? 1 : -1;
	}

	public static int exists(String fileName) throws IOException {
		return getFile(fileName).exists() ? 1 : -1;
	}

	public static boolean getIsHidden(String fileName) throws IOException {
		return getFile(fileName).isHidden();
	}

	public static long getLastModified(String fileName) throws IOException {
		return getFile(fileName).lastModified();
	}

	public static boolean isDirectory(String fileName) throws IOException {
		return getFile(fileName).isDirectory();
	}

	public int length(int fileDescriptor) throws IOException {
		RandomAccessFile file = OPENED_FILES.get(fileDescriptor);
		if (file == null) {
			return -1;
		}
		return (int) file.length();
	}

	public static String[] list(String pathName) throws IOException {
		return list(pathName, false);
	}

	public static String[] list(String pathName, boolean includeHidden) throws IOException {
		FilenameFilter filter = (dir, name) -> {
            if (!includeHidden && name.charAt(0) == '.') return false;
            try {
                getFile(pathName + (pathName.endsWith("/") ? "" : "/") + name);
                return true;
            } catch (SecurityException denied) {
                return false;
            }
        };
		String[] files = getFile(pathName).list(filter);
		if (files == null) {
			return new String[0];
		}
		Arrays.sort(files);
		return files;
	}

	public int open(String fileName) throws IOException {
		java.io.File file = getFile(fileName, 3);
		RandomAccessFile raf = new RandomAccessFile(file, "rw");
		int fd = ++lastDescriptor;
		OPENED_FILES.append(fd, raf);
		return fd;
	}

	public int read(int fileDescriptor, byte[] buf, int offset, int numBytes) throws IOException {
		RandomAccessFile file = OPENED_FILES.get(fileDescriptor);
		if (file == null) {
			return -1;
		}
		return file.read(buf, offset, numBytes);
	}

	public static int rename(String source, String dest) {
		return getFile(source, 2).renameTo(getFile(dest, 2)) ? 1 : -1;
	}

	public int seek(int fileDescriptor, int seekpos) throws IOException {
		RandomAccessFile file = OPENED_FILES.get(fileDescriptor);
		if (file == null) {
			return -1;
		}
		file.seek(seekpos);
		return (int) file.getFilePointer();
	}

	@SuppressLint("UsableSpace")
	public static int spaceAvailable() throws IOException {
		return (int) Math.min(Integer.MAX_VALUE, VirtualFileSystem.resolve("siemens:/", "").getUsableSpace());
	}

	public static void truncate(int fileDescriptor, int size) throws IOException {
		RandomAccessFile file = OPENED_FILES.get(fileDescriptor);
		if (file == null) {
			return;
		}
		file.setLength(size);
	}

	public int write(int fileDescriptor, byte[] buf, int offset, int numBytes) throws IOException {
		RandomAccessFile file = OPENED_FILES.get(fileDescriptor);
		if (file == null) {
			return -1;
		}
		file.write(buf, offset, numBytes);
		return numBytes;
	}

    private static java.io.File getFile(String fileName) {
        return getFile(fileName, 1);
    }

    private static java.io.File getFile(String fileName, int access) {
        if (fileName == null) throw new NullPointerException("fileName");
        String normalized = fileName.replace('\\', '/');
        int colon = normalized.indexOf(':');
        String root;
        String path;
        if (colon == -1) {
            root = "siemens:/";
            path = normalized;
        } else {
            if (colon == 0 || colon + 1 >= normalized.length()
                    || normalized.charAt(colon + 1) != '/') {
                throw new IllegalArgumentException("Invalid Siemens filesystem path");
            }
            root = normalized.substring(0, colon + 2);
            path = normalized.substring(colon + 2);
        }
        if ((access & 2) != 0 && path.isEmpty()) {
            throw new SecurityException("Cannot modify a virtual filesystem root");
        }
        try {
            java.io.File result = VirtualFileSystem.resolve(root, path);
            if ((access & 1) != 0) MidletPermissionManager.requireFileAccess(root, false);
            if ((access & 2) != 0) {
                VirtualFileSystem.checkMutation(root, result);
                MidletPermissionManager.requireFileAccess(root, true);
            }
            return result;
        } catch (IOException error) {
            throw new SecurityException("Invalid Siemens filesystem path", error);
        }
    }
}
