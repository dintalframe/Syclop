// Syclop maintained reconstruction of custom Media3 transport changes; not historical private source.
package androidx.media3.common;

import androidx.media3.common.util.UnstableApi;

@UnstableApi
public final class NuvioEngineConfig {
    public static final int STOCK_EXTRACTOR_SCRATCH_SIZE = 4096;
    public static final int NUVIO_EXTRACTOR_SCRATCH_SIZE = 65536;
    private static volatile NuvioEngineConfig instance = stockMode();
    private final AllocationMode allocationMode;
    private final DataPipelineMode dataPipelineMode;
    private final int extractorScratchSize;

    public enum AllocationMode {
        STOCK_HEAP,
        NATIVE_OFF_HEAP
    }

    public enum DataPipelineMode {
        STOCK,
        ZERO_COPY
    }

    private NuvioEngineConfig(Builder builder) {
        this.allocationMode = builder.allocationMode;
        this.dataPipelineMode = builder.dataPipelineMode;
        this.extractorScratchSize = builder.extractorScratchSize;
    }

    public static NuvioEngineConfig get() {
        return instance;
    }

    public static void set(NuvioEngineConfig config) {
        instance = config;
    }

    public static NuvioEngineConfig nuvioMode() {
        return new Builder().setAllocationMode(AllocationMode.NATIVE_OFF_HEAP).setDataPipelineMode(DataPipelineMode.ZERO_COPY).setExtractorScratchSize(NUVIO_EXTRACTOR_SCRATCH_SIZE).build();
    }

    public static NuvioEngineConfig stockMode() {
        return new Builder().setAllocationMode(AllocationMode.STOCK_HEAP).setDataPipelineMode(DataPipelineMode.STOCK).setExtractorScratchSize(STOCK_EXTRACTOR_SCRATCH_SIZE).build();
    }

    public AllocationMode getAllocationMode() {
        return this.allocationMode;
    }

    public DataPipelineMode getDataPipelineMode() {
        return this.dataPipelineMode;
    }

    public int getExtractorScratchSize() {
        return this.extractorScratchSize;
    }

    public boolean isNativeAllocationEnabled() {
        return this.allocationMode == AllocationMode.NATIVE_OFF_HEAP;
    }

    public boolean isZeroCopyEnabled() {
        return this.dataPipelineMode == DataPipelineMode.ZERO_COPY;
    }

    public static final class Builder {
        private AllocationMode allocationMode = AllocationMode.STOCK_HEAP;
        private DataPipelineMode dataPipelineMode = DataPipelineMode.STOCK;
        private int extractorScratchSize = NuvioEngineConfig.STOCK_EXTRACTOR_SCRATCH_SIZE;

        public Builder setAllocationMode(AllocationMode allocationMode) {
            this.allocationMode = allocationMode;
            return this;
        }

        public Builder setDataPipelineMode(DataPipelineMode dataPipelineMode) {
            this.dataPipelineMode = dataPipelineMode;
            return this;
        }

        public Builder setExtractorScratchSize(int extractorScratchSize) {
            this.extractorScratchSize = extractorScratchSize;
            return this;
        }

        public NuvioEngineConfig build() {
            return new NuvioEngineConfig(this);
        }
    }
}
