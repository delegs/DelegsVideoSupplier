import java.io.File;

public class DelegsVideoSupplierService {
	private final File VIDEO_DIR_FILE;

	public DelegsVideoSupplierService(String videoPath) {
		VIDEO_DIR_FILE = new File(videoPath);
	}

	public File getVideoPath() {
		return VIDEO_DIR_FILE.getAbsoluteFile();
	}
}
