package org.rhq.metrics.impl.cassandra;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.datastax.driver.core.DataType;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TupleType;
import com.datastax.driver.core.TupleValue;
import com.datastax.driver.core.UDTValue;
import com.datastax.driver.core.UserType;

import org.rhq.metrics.core.AggregatedValue;
import org.rhq.metrics.core.AggregationTemplate;
import org.rhq.metrics.core.Interval;
import org.rhq.metrics.core.MetricType;
import org.rhq.metrics.core.NumericData;
import org.rhq.metrics.core.RetentionSettings;
import org.rhq.metrics.core.Tenant;

/**
 * This class will eventually supplant the existing DataAccess class.
 *
 * @author John Sanda
 */
public class DataAccess2 {

    private Session session;

    private PreparedStatement insertTenant;

    private PreparedStatement findTenants;

    private PreparedStatement addNumericAttributes;

    private PreparedStatement insertNumericData;

    private PreparedStatement findNumericData;

    public DataAccess2(Session session) {
        this.session = session;
        initPreparedStatements();
    }

    private void initPreparedStatements() {
        insertTenant = session.prepare("INSERT INTO tenants (id, retentions, aggregation_templates) VALUES (?, ?, ?)");

        findTenants = session.prepare("SELECT id, retentions, aggregation_templates FROM tenants");

        addNumericAttributes = session.prepare(
            "UPDATE numeric_data " +
            "SET attributes = attributes + ? " +
            "WHERE tenant_id = ? AND metric = ? AND interval = ? AND dpart = ?");

//        insertNumericData = session.prepare(
//            "INSERT INTO numeric_data (tenant_id, metric, interval, dpart, time, raw) " +
//            "VALUES (?, ?, ?, ?, ?, ?)");

        insertNumericData = session.prepare(
            "UPDATE numeric_data " +
            "SET attributes = attributes + ?, raw = ?, aggregates = ? " +
            "WHERE tenant_id = ? AND metric = ? AND interval = ? AND dpart = ? AND time = ?");

        findNumericData = session.prepare(
            "SELECT tenant_id, metric, interval, dpart, time, attributes, raw, aggregates " +
            "FROM numeric_data " +
            "WHERE tenant_id = ? AND metric = ? AND interval = ? AND dpart = ?");
    }

    public ResultSetFuture insertTenant(Tenant tenant) {
        UserType aggregationTemplateType = session.getCluster().getMetadata().getKeyspace("rhq")
            .getUserType("aggregation_template");
        List<UDTValue> templateValues = new ArrayList<>(tenant.getAggregationTemplates().size());
        for (AggregationTemplate template : tenant.getAggregationTemplates()) {
            UDTValue value = aggregationTemplateType.newValue();
            value.setString("type", template.getType().getCode());
            value.setString("interval", template.getInterval().toString());
            value.setSet("fns", template.getFunctions());
            templateValues.add(value);
        }

        Map<TupleValue, Integer> retentions = new HashMap<>();
        for (RetentionSettings.RetentionKey key : tenant.getRetentionSettings().keySet()) {
            TupleType metricType = TupleType.of(DataType.text(), DataType.text());
            TupleValue tuple = metricType.newValue();
            tuple.setString(0, key.metricType.getCode());
            if (key.interval == null) {
                tuple.setString(1, null);
            } else {
                tuple.setString(1, key.interval.toString());
            }
            retentions.put(tuple, tenant.getRetentionSettings().get(key));
        }

        return session.executeAsync(insertTenant.bind(tenant.getId(), retentions, templateValues));
    }

    public Set<Tenant> findTenants() {
        ResultSet resultSet = session.execute(findTenants.bind());
        Set<Tenant> tenants = new HashSet<>();
        for (Row row : resultSet) {
            Tenant tenant = new Tenant();
            tenant.setId(row.getString(0));

            Map<TupleValue, Integer> retentions = row.getMap(1, TupleValue.class, Integer.class);
            for (Map.Entry<TupleValue, Integer> entry : retentions.entrySet()) {
                MetricType metricType = MetricType.fromCode(entry.getKey().getString(0));
                if (entry.getKey().isNull(1)) {
                    tenant.setRetention(metricType, entry.getValue());
                } else {
                    Interval interval = Interval.parse(entry.getKey().getString(1));
                    tenant.setRetention(metricType, interval, entry.getValue());
                }
            }

            List<UDTValue> templateValues = row.getList(2, UDTValue.class);
            for (UDTValue value : templateValues) {
                tenant.addAggregationTemplate(new AggregationTemplate()
                    .setType(MetricType.fromCode(value.getString("type")))
                    .setInterval(Interval.parse(value.getString("interval")))
                    .setFunctions(value.getSet("fns", String.class)));
            }

            tenants.add(tenant);
        }
        return tenants;
    }

    public ResultSetFuture addNumericAttributes(String tenantId, String metric, Interval interval, long dpart,
        Map<String, String> attributes) {
        return session.executeAsync(addNumericAttributes.bind(attributes, tenantId, metric, interval.toString(),
            dpart));
    }

    public ResultSetFuture insertNumericData(NumericData data) {
        // TODO determine if we should use separate queries/methods for raw vs aggregated data

        UserType aggregateDataType = session.getCluster().getMetadata().getKeyspace("rhq")
            .getUserType("aggregate_data");
        Set<UDTValue> aggregateDataValues = new HashSet<>();

        for (AggregatedValue v : data.getAggregatedValues()) {
            aggregateDataValues.add(aggregateDataType.newValue()
                .setString("type", v.getType())
                .setDouble("value", v.getValue())
                .setUUID("time", v.getTimeUUID())
                .setString("src_metric", v.getSrcMetric())
                .setString("src_metric_interval", getInterval(v.getSrcMetricInterval())));
        }

        return session.executeAsync(insertNumericData.bind(data.getAttributes(), data.getValue(), aggregateDataValues,
            data.getTenantId(), data.getMetric(), getInterval(data.getInterval()), 0L, data.getTimeUUID()));
    }

    private final String getInterval(Interval interval) {
        return interval == null ? "" : interval.toString();
    }

    public ResultSetFuture findNumericData(String tenantId, String metric, Interval interval, long dpart) {
        return session.executeAsync(findNumericData.bind(tenantId, metric, interval.toString(), dpart));
    }

}