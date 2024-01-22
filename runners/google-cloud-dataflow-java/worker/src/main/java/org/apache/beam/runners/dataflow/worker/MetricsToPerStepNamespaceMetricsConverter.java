/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.dataflow.worker;

import com.google.api.services.dataflow.model.Base2Exponent;
import com.google.api.services.dataflow.model.BucketOptions;
import com.google.api.services.dataflow.model.DataflowHistogramValue;
import com.google.api.services.dataflow.model.Linear;
import com.google.api.services.dataflow.model.MetricValue;
import com.google.api.services.dataflow.model.OutlierStats;
import com.google.api.services.dataflow.model.PerStepNamespaceMetrics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.annotation.Nullable;
import org.apache.beam.sdk.io.gcp.bigquery.BigQuerySinkMetrics;
import org.apache.beam.sdk.metrics.MetricName;
import org.apache.beam.sdk.util.HistogramData;

public class MetricsToPerStepNamespaceMetricsConverter {
  private static @Nullable MetricValue convertCounterToMetricValue(MetricName counter, Long value) {
    if (value == 0 || !counter.getNamespace().equals(BigQuerySinkMetrics.METRICS_NAMESPACE)) {
      return null;
    }

    BigQuerySinkMetrics.ParsedMetricName labeledName =
        BigQuerySinkMetrics.parseMetricName(counter.getName());
    if (labeledName == null || labeledName.getBaseName().isEmpty()) {
      return null;
    }

    MetricValue val =
        new MetricValue()
            .setMetric(labeledName.getBaseName())
            .setMetricLabels(labeledName.getMetricLabels())
            .setValueInt64(value);
    return val;
  }

  private static @Nullable MetricValue convertHistogramToMetricValue(
      MetricName name, HistogramData value) {
    if (value.getTotalCount() == 0L) {
      return null;
    }

    BigQuerySinkMetrics.ParsedMetricName labeledName =
        BigQuerySinkMetrics.parseMetricName(name.getName());
    if (labeledName == null || labeledName.getBaseName().isEmpty()) {
      return null;
    }

    DataflowHistogramValue histogramValue = new DataflowHistogramValue();
    int numberOfBuckets = value.getBucketType().getNumBuckets();

    if (value.getBucketType() instanceof HistogramData.LinearBuckets) {
      HistogramData.LinearBuckets buckets = (HistogramData.LinearBuckets) value.getBucketType();
      Linear linearOptions =
          new Linear()
              .setNumberOfBuckets(numberOfBuckets)
              .setWidth(buckets.getWidth())
              .setStart(buckets.getStart());
      histogramValue.setBucketOptions(new BucketOptions().setLinear(linearOptions));
    } else if (value.getBucketType() instanceof HistogramData.ExponentialBuckets) {
      HistogramData.ExponentialBuckets buckets =
          (HistogramData.ExponentialBuckets) value.getBucketType();
      Base2Exponent expoenntialOptions =
          new Base2Exponent().setNumberOfBuckets(numberOfBuckets).setScale(buckets.getScale());
      histogramValue.setBucketOptions(new BucketOptions().setExponential(expoenntialOptions));
    } else {
      return null;
    }

    histogramValue.setCount(value.getTotalCount());
    List<Long> bucketCounts = new ArrayList<>(value.getBucketType().getNumBuckets());

    for (int i = 0; i < value.getBucketType().getNumBuckets(); i++) {
      bucketCounts.add(value.getCount(i));
    }

    // Remove trailing 0 buckets.
    for (int i = bucketCounts.size() - 1; i >= 0; i--) {
      if (bucketCounts.get(i) != 0) {
        break;
      }
      bucketCounts.remove(i);
    }

    histogramValue.setBucketCounts(bucketCounts);

    OutlierStats outlierStats =
        new OutlierStats()
            .setOverflowCount(value.getTopBucketCount())
            .setOverflowMean(value.getTopBucketMean())
            .setUnderflowCount(value.getBottomBucketCount())
            .setUnderflowMean(value.getBottomBucketMean());

    histogramValue.setOutlierStats(outlierStats);

    return new MetricValue()
        .setMetric(labeledName.getBaseName())
        .setMetricLabels(labeledName.getMetricLabels())
        .setValueHistogram(histogramValue);
  }

  public static Collection<PerStepNamespaceMetrics> convert(
      String stepName, Map<MetricName, Long> counters, Map<MetricName, HistogramData> histograms) {

    Map<String, PerStepNamespaceMetrics> metricsByNamespace = new HashMap<>();

    for (Entry<MetricName, Long> entry : counters.entrySet()) {
      MetricName metricName = entry.getKey();
      MetricValue metricValue = convertCounterToMetricValue(metricName, entry.getValue());
      if (metricValue == null) {
        continue;
      }

      PerStepNamespaceMetrics stepNamespaceMetrics =
          metricsByNamespace.get(metricName.getNamespace());
      if (stepNamespaceMetrics == null) {
        stepNamespaceMetrics =
            new PerStepNamespaceMetrics()
                .setMetricValues(new ArrayList<>())
                .setOriginalStep(stepName)
                .setMetricsNamespace(metricName.getNamespace());
        metricsByNamespace.put(metricName.getNamespace(), stepNamespaceMetrics);
      }

      stepNamespaceMetrics.getMetricValues().add(metricValue);
    }

    for (Entry<MetricName, HistogramData> entry : histograms.entrySet()) {
      MetricName metricName = entry.getKey();
      MetricValue metricValue = convertHistogramToMetricValue(metricName, entry.getValue());
      if (metricValue == null) {
        continue;
      }

      PerStepNamespaceMetrics stepNamespaceMetrics =
          metricsByNamespace.get(metricName.getNamespace());
      if (stepNamespaceMetrics == null) {
        stepNamespaceMetrics =
            new PerStepNamespaceMetrics()
                .setMetricValues(new ArrayList<>())
                .setOriginalStep(stepName)
                .setMetricsNamespace(metricName.getNamespace());
        metricsByNamespace.put(metricName.getNamespace(), stepNamespaceMetrics);
      }

      stepNamespaceMetrics.getMetricValues().add(metricValue);
    }

    return metricsByNamespace.values();
  }
}
