/**
 * Copyright (C) 2016 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.market.curve.node;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.joda.beans.Bean;
import org.joda.beans.BeanDefinition;
import org.joda.beans.ImmutableBean;
import org.joda.beans.ImmutableDefaults;
import org.joda.beans.ImmutablePreBuild;
import org.joda.beans.JodaBeanUtils;
import org.joda.beans.MetaProperty;
import org.joda.beans.Property;
import org.joda.beans.PropertyDefinition;
import org.joda.beans.impl.direct.DirectFieldsBeanBuilder;
import org.joda.beans.impl.direct.DirectMetaBean;
import org.joda.beans.impl.direct.DirectMetaProperty;
import org.joda.beans.impl.direct.DirectMetaPropertyMap;

import com.google.common.collect.ImmutableSet;
import com.opengamma.strata.basics.ReferenceData;
import com.opengamma.strata.data.MarketData;
import com.opengamma.strata.data.ObservableId;
import com.opengamma.strata.market.ValueType;
import com.opengamma.strata.market.curve.CurveNode;
import com.opengamma.strata.market.curve.CurveNodeDate;
import com.opengamma.strata.market.param.DatedParameterMetadata;
import com.opengamma.strata.market.param.LabelDateParameterMetadata;
import com.opengamma.strata.market.param.TenorDateParameterMetadata;
import com.opengamma.strata.product.common.BuySell;
import com.opengamma.strata.product.rate.IborRateComputation;
import com.opengamma.strata.product.swap.PaymentPeriod;
import com.opengamma.strata.product.swap.RateAccrualPeriod;
import com.opengamma.strata.product.swap.RatePaymentPeriod;
import com.opengamma.strata.product.swap.ResolvedSwapLeg;
import com.opengamma.strata.product.swap.ResolvedSwapTrade;
import com.opengamma.strata.product.swap.SwapLeg;
import com.opengamma.strata.product.swap.SwapLegType;
import com.opengamma.strata.product.swap.SwapTrade;
import com.opengamma.strata.product.swap.type.OvernightIborSwapTemplate;

/**
 * A curve node whose instrument is an Overnight-Ibor interest rate swap.
 * <p>
 * The spread or market quote is on the first Ibor leg.
 * <p>
 * The trade produced by the node will be a spread receiver (SELL) for a positive quantity
 * and a payer (BUY) for a negative quantity. 
 * This convention is line with other nodes where a positive quantity is similar to long a bond or deposit.
 */
@BeanDefinition
public final class OvernightIborSwapCurveNode
    implements CurveNode, ImmutableBean, Serializable {

  /**
   * The template for the swap associated with this node.
   */
  @PropertyDefinition(validate = "notNull")
  private final OvernightIborSwapTemplate template;
  /**
   * The identifier of the market data value that provides the rate.
   */
  @PropertyDefinition(validate = "notNull")
  private final ObservableId rateId;
  /**
   * The additional spread added to the rate.
   */
  @PropertyDefinition
  private final double additionalSpread;
  /**
   * The label to use for the node, defaulted.
   * <p>
   * When building, this will default based on the tenor if not specified.
   */
  @PropertyDefinition(validate = "notEmpty", overrideGet = true)
  private final String label;
  /**
   * The method by which the date of the node is calculated, defaulted to 'End'.
   */
  @PropertyDefinition
  private final CurveNodeDate date;

  //-------------------------------------------------------------------------
  /**
   * Obtains a curve node for an Overnight-Ibor interest rate swap using the
   * specified instrument template and rate.
   * <p>
   * A suitable default label will be created.
   *
   * @param template  the template used for building the instrument for the node
   * @param rateId  the identifier of the market rate used when building the instrument for the node
   * @return a node whose instrument is built from the template using a market rate
   */
  public static OvernightIborSwapCurveNode of(OvernightIborSwapTemplate template, ObservableId rateId) {
    return of(template, rateId, 0d);
  }

  /**
   * Obtains a curve node for an Overnight-Ibor interest rate swap using the
   * specified instrument template, rate key and spread.
   * <p>
   * A suitable default label will be created.
   *
   * @param template  the template defining the node instrument
   * @param rateId  the identifier of the market data providing the rate for the node instrument
   * @param additionalSpread  the additional spread amount added to the spread
   * @return a node whose instrument is built from the template using a market rate
   */
  public static OvernightIborSwapCurveNode of(OvernightIborSwapTemplate template, ObservableId rateId, double additionalSpread) {
    return builder()
        .template(template)
        .rateId(rateId)
        .additionalSpread(additionalSpread)
        .build();
  }

  /**
   * Obtains a curve node for an Overnight-Ibor interest rate swap using the
   * specified instrument template, rate key, spread and label.
   *
   * @param template  the template defining the node instrument
   * @param rateId  the identifier of the market data providing the rate for the node instrument
   * @param additionalSpread  the additional spread amount added to the spread
   * @param label  the label to use for the node
   * @return a node whose instrument is built from the template using a market rate
   */
  public static OvernightIborSwapCurveNode of(
      OvernightIborSwapTemplate template,
      ObservableId rateId,
      double additionalSpread,
      String label) {

    return new OvernightIborSwapCurveNode(template, rateId, additionalSpread, label, CurveNodeDate.END);
  }

  @ImmutableDefaults
  private static void applyDefaults(Builder builder) {
    builder.date = CurveNodeDate.END;
  }

  @ImmutablePreBuild
  private static void preBuild(Builder builder) {
    if (builder.label == null && builder.template != null) {
      builder.label = builder.template.getTenor().toString();
    }
  }

  //-------------------------------------------------------------------------
  @Override
  public Set<ObservableId> requirements() {
    return ImmutableSet.of(rateId);
  }

  @Override
  public DatedParameterMetadata metadata(LocalDate valuationDate, ReferenceData refData) {
    LocalDate nodeDate = date.calculate(
        () -> calculateEnd(valuationDate, refData),
        () -> calculateLastFixingDate(valuationDate, refData));
    if (date.isFixed()) {
      return LabelDateParameterMetadata.of(nodeDate, label);
    }
    return TenorDateParameterMetadata.of(nodeDate, template.getTenor(), label);
  }

  // calculate the end date
  private LocalDate calculateEnd(LocalDate valuationDate, ReferenceData refData) {
    SwapTrade trade = template.createTrade(valuationDate, BuySell.BUY, 1, 1, refData);
    return trade.getProduct().getEndDate().adjusted(refData);
  }

  // calculate the last fixing date
  private LocalDate calculateLastFixingDate(LocalDate valuationDate, ReferenceData refData) {
    SwapTrade trade = template.createTrade(valuationDate, BuySell.BUY, 1, 1, refData);
    SwapLeg iborLeg = trade.getProduct().getLegs(SwapLegType.IBOR).get(0);
    ResolvedSwapLeg iborLegExpanded = iborLeg.resolve(refData);
    List<PaymentPeriod> periods = iborLegExpanded.getPaymentPeriods();
    int nbPeriods = periods.size();
    RatePaymentPeriod lastPeriod = (RatePaymentPeriod) periods.get(nbPeriods - 1);
    List<RateAccrualPeriod> accruals = lastPeriod.getAccrualPeriods();
    int nbAccruals = accruals.size();
    IborRateComputation ibor = (IborRateComputation) accruals.get(nbAccruals - 1).getRateComputation();
    return ibor.getFixingDate();
  }

  @Override
  public SwapTrade trade(double quantity, MarketData marketData, ReferenceData refData) {
    double fixedRate = marketData.getValue(rateId) + additionalSpread;
    BuySell buySell = quantity > 0 ? BuySell.SELL : BuySell.BUY;
    return template.createTrade(marketData.getValuationDate(), buySell, Math.abs(quantity), fixedRate, refData);
  }

  @Override
  public ResolvedSwapTrade resolvedTrade(double quantity, MarketData marketData, ReferenceData refData) {
    return trade(quantity, marketData, refData).resolve(refData);
  }

  @Override
  public double initialGuess(MarketData marketData, ValueType valueType) {
    if (ValueType.ZERO_RATE.equals(valueType) || ValueType.FORWARD_RATE.equals(valueType)) {
      return marketData.getValue(rateId);
    }
    if (ValueType.DISCOUNT_FACTOR.equals(valueType)) {
      double approximateMaturity = template.getPeriodToStart().plus(template.getTenor()).toTotalMonths() / 12.0d;
      return Math.exp(-approximateMaturity * marketData.getValue(rateId));
    }
    return 0d;
  }

  //-------------------------------------------------------------------------
  /**
   * Returns a copy of this node with the specified date.
   * 
   * @param date  the date to use
   * @return the node based on this node with the specified date
   */
  public OvernightIborSwapCurveNode withDate(CurveNodeDate date) {
    return new OvernightIborSwapCurveNode(template, rateId, additionalSpread, label, date);
  }

  //------------------------- AUTOGENERATED START -------------------------
  ///CLOVER:OFF
  /**
   * The meta-bean for {@code OvernightIborSwapCurveNode}.
   * @return the meta-bean, not null
   */
  public static OvernightIborSwapCurveNode.Meta meta() {
    return OvernightIborSwapCurveNode.Meta.INSTANCE;
  }

  static {
    JodaBeanUtils.registerMetaBean(OvernightIborSwapCurveNode.Meta.INSTANCE);
  }

  /**
   * The serialization version id.
   */
  private static final long serialVersionUID = 1L;

  /**
   * Returns a builder used to create an instance of the bean.
   * @return the builder, not null
   */
  public static OvernightIborSwapCurveNode.Builder builder() {
    return new OvernightIborSwapCurveNode.Builder();
  }

  private OvernightIborSwapCurveNode(
      OvernightIborSwapTemplate template,
      ObservableId rateId,
      double additionalSpread,
      String label,
      CurveNodeDate date) {
    JodaBeanUtils.notNull(template, "template");
    JodaBeanUtils.notNull(rateId, "rateId");
    JodaBeanUtils.notEmpty(label, "label");
    this.template = template;
    this.rateId = rateId;
    this.additionalSpread = additionalSpread;
    this.label = label;
    this.date = date;
  }

  @Override
  public OvernightIborSwapCurveNode.Meta metaBean() {
    return OvernightIborSwapCurveNode.Meta.INSTANCE;
  }

  @Override
  public <R> Property<R> property(String propertyName) {
    return metaBean().<R>metaProperty(propertyName).createProperty(this);
  }

  @Override
  public Set<String> propertyNames() {
    return metaBean().metaPropertyMap().keySet();
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the template for the swap associated with this node.
   * @return the value of the property, not null
   */
  public OvernightIborSwapTemplate getTemplate() {
    return template;
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the identifier of the market data value that provides the rate.
   * @return the value of the property, not null
   */
  public ObservableId getRateId() {
    return rateId;
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the additional spread added to the rate.
   * @return the value of the property
   */
  public double getAdditionalSpread() {
    return additionalSpread;
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the label to use for the node, defaulted.
   * <p>
   * When building, this will default based on the tenor if not specified.
   * @return the value of the property, not empty
   */
  @Override
  public String getLabel() {
    return label;
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the method by which the date of the node is calculated, defaulted to 'End'.
   * @return the value of the property
   */
  public CurveNodeDate getDate() {
    return date;
  }

  //-----------------------------------------------------------------------
  /**
   * Returns a builder that allows this bean to be mutated.
   * @return the mutable builder, not null
   */
  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (obj != null && obj.getClass() == this.getClass()) {
      OvernightIborSwapCurveNode other = (OvernightIborSwapCurveNode) obj;
      return JodaBeanUtils.equal(template, other.template) &&
          JodaBeanUtils.equal(rateId, other.rateId) &&
          JodaBeanUtils.equal(additionalSpread, other.additionalSpread) &&
          JodaBeanUtils.equal(label, other.label) &&
          JodaBeanUtils.equal(date, other.date);
    }
    return false;
  }

  @Override
  public int hashCode() {
    int hash = getClass().hashCode();
    hash = hash * 31 + JodaBeanUtils.hashCode(template);
    hash = hash * 31 + JodaBeanUtils.hashCode(rateId);
    hash = hash * 31 + JodaBeanUtils.hashCode(additionalSpread);
    hash = hash * 31 + JodaBeanUtils.hashCode(label);
    hash = hash * 31 + JodaBeanUtils.hashCode(date);
    return hash;
  }

  @Override
  public String toString() {
    StringBuilder buf = new StringBuilder(192);
    buf.append("OvernightIborSwapCurveNode{");
    buf.append("template").append('=').append(template).append(',').append(' ');
    buf.append("rateId").append('=').append(rateId).append(',').append(' ');
    buf.append("additionalSpread").append('=').append(additionalSpread).append(',').append(' ');
    buf.append("label").append('=').append(label).append(',').append(' ');
    buf.append("date").append('=').append(JodaBeanUtils.toString(date));
    buf.append('}');
    return buf.toString();
  }

  //-----------------------------------------------------------------------
  /**
   * The meta-bean for {@code OvernightIborSwapCurveNode}.
   */
  public static final class Meta extends DirectMetaBean {
    /**
     * The singleton instance of the meta-bean.
     */
    static final Meta INSTANCE = new Meta();

    /**
     * The meta-property for the {@code template} property.
     */
    private final MetaProperty<OvernightIborSwapTemplate> template = DirectMetaProperty.ofImmutable(
        this, "template", OvernightIborSwapCurveNode.class, OvernightIborSwapTemplate.class);
    /**
     * The meta-property for the {@code rateId} property.
     */
    private final MetaProperty<ObservableId> rateId = DirectMetaProperty.ofImmutable(
        this, "rateId", OvernightIborSwapCurveNode.class, ObservableId.class);
    /**
     * The meta-property for the {@code additionalSpread} property.
     */
    private final MetaProperty<Double> additionalSpread = DirectMetaProperty.ofImmutable(
        this, "additionalSpread", OvernightIborSwapCurveNode.class, Double.TYPE);
    /**
     * The meta-property for the {@code label} property.
     */
    private final MetaProperty<String> label = DirectMetaProperty.ofImmutable(
        this, "label", OvernightIborSwapCurveNode.class, String.class);
    /**
     * The meta-property for the {@code date} property.
     */
    private final MetaProperty<CurveNodeDate> date = DirectMetaProperty.ofImmutable(
        this, "date", OvernightIborSwapCurveNode.class, CurveNodeDate.class);
    /**
     * The meta-properties.
     */
    private final Map<String, MetaProperty<?>> metaPropertyMap$ = new DirectMetaPropertyMap(
        this, null,
        "template",
        "rateId",
        "additionalSpread",
        "label",
        "date");

    /**
     * Restricted constructor.
     */
    private Meta() {
    }

    @Override
    protected MetaProperty<?> metaPropertyGet(String propertyName) {
      switch (propertyName.hashCode()) {
        case -1321546630:  // template
          return template;
        case -938107365:  // rateId
          return rateId;
        case 291232890:  // additionalSpread
          return additionalSpread;
        case 102727412:  // label
          return label;
        case 3076014:  // date
          return date;
      }
      return super.metaPropertyGet(propertyName);
    }

    @Override
    public OvernightIborSwapCurveNode.Builder builder() {
      return new OvernightIborSwapCurveNode.Builder();
    }

    @Override
    public Class<? extends OvernightIborSwapCurveNode> beanType() {
      return OvernightIborSwapCurveNode.class;
    }

    @Override
    public Map<String, MetaProperty<?>> metaPropertyMap() {
      return metaPropertyMap$;
    }

    //-----------------------------------------------------------------------
    /**
     * The meta-property for the {@code template} property.
     * @return the meta-property, not null
     */
    public MetaProperty<OvernightIborSwapTemplate> template() {
      return template;
    }

    /**
     * The meta-property for the {@code rateId} property.
     * @return the meta-property, not null
     */
    public MetaProperty<ObservableId> rateId() {
      return rateId;
    }

    /**
     * The meta-property for the {@code additionalSpread} property.
     * @return the meta-property, not null
     */
    public MetaProperty<Double> additionalSpread() {
      return additionalSpread;
    }

    /**
     * The meta-property for the {@code label} property.
     * @return the meta-property, not null
     */
    public MetaProperty<String> label() {
      return label;
    }

    /**
     * The meta-property for the {@code date} property.
     * @return the meta-property, not null
     */
    public MetaProperty<CurveNodeDate> date() {
      return date;
    }

    //-----------------------------------------------------------------------
    @Override
    protected Object propertyGet(Bean bean, String propertyName, boolean quiet) {
      switch (propertyName.hashCode()) {
        case -1321546630:  // template
          return ((OvernightIborSwapCurveNode) bean).getTemplate();
        case -938107365:  // rateId
          return ((OvernightIborSwapCurveNode) bean).getRateId();
        case 291232890:  // additionalSpread
          return ((OvernightIborSwapCurveNode) bean).getAdditionalSpread();
        case 102727412:  // label
          return ((OvernightIborSwapCurveNode) bean).getLabel();
        case 3076014:  // date
          return ((OvernightIborSwapCurveNode) bean).getDate();
      }
      return super.propertyGet(bean, propertyName, quiet);
    }

    @Override
    protected void propertySet(Bean bean, String propertyName, Object newValue, boolean quiet) {
      metaProperty(propertyName);
      if (quiet) {
        return;
      }
      throw new UnsupportedOperationException("Property cannot be written: " + propertyName);
    }

  }

  //-----------------------------------------------------------------------
  /**
   * The bean-builder for {@code OvernightIborSwapCurveNode}.
   */
  public static final class Builder extends DirectFieldsBeanBuilder<OvernightIborSwapCurveNode> {

    private OvernightIborSwapTemplate template;
    private ObservableId rateId;
    private double additionalSpread;
    private String label;
    private CurveNodeDate date;

    /**
     * Restricted constructor.
     */
    private Builder() {
      applyDefaults(this);
    }

    /**
     * Restricted copy constructor.
     * @param beanToCopy  the bean to copy from, not null
     */
    private Builder(OvernightIborSwapCurveNode beanToCopy) {
      this.template = beanToCopy.getTemplate();
      this.rateId = beanToCopy.getRateId();
      this.additionalSpread = beanToCopy.getAdditionalSpread();
      this.label = beanToCopy.getLabel();
      this.date = beanToCopy.getDate();
    }

    //-----------------------------------------------------------------------
    @Override
    public Object get(String propertyName) {
      switch (propertyName.hashCode()) {
        case -1321546630:  // template
          return template;
        case -938107365:  // rateId
          return rateId;
        case 291232890:  // additionalSpread
          return additionalSpread;
        case 102727412:  // label
          return label;
        case 3076014:  // date
          return date;
        default:
          throw new NoSuchElementException("Unknown property: " + propertyName);
      }
    }

    @Override
    public Builder set(String propertyName, Object newValue) {
      switch (propertyName.hashCode()) {
        case -1321546630:  // template
          this.template = (OvernightIborSwapTemplate) newValue;
          break;
        case -938107365:  // rateId
          this.rateId = (ObservableId) newValue;
          break;
        case 291232890:  // additionalSpread
          this.additionalSpread = (Double) newValue;
          break;
        case 102727412:  // label
          this.label = (String) newValue;
          break;
        case 3076014:  // date
          this.date = (CurveNodeDate) newValue;
          break;
        default:
          throw new NoSuchElementException("Unknown property: " + propertyName);
      }
      return this;
    }

    @Override
    public Builder set(MetaProperty<?> property, Object value) {
      super.set(property, value);
      return this;
    }

    @Override
    public Builder setString(String propertyName, String value) {
      setString(meta().metaProperty(propertyName), value);
      return this;
    }

    @Override
    public Builder setString(MetaProperty<?> property, String value) {
      super.setString(property, value);
      return this;
    }

    @Override
    public Builder setAll(Map<String, ? extends Object> propertyValueMap) {
      super.setAll(propertyValueMap);
      return this;
    }

    @Override
    public OvernightIborSwapCurveNode build() {
      preBuild(this);
      return new OvernightIborSwapCurveNode(
          template,
          rateId,
          additionalSpread,
          label,
          date);
    }

    //-----------------------------------------------------------------------
    /**
     * Sets the template for the swap associated with this node.
     * @param template  the new value, not null
     * @return this, for chaining, not null
     */
    public Builder template(OvernightIborSwapTemplate template) {
      JodaBeanUtils.notNull(template, "template");
      this.template = template;
      return this;
    }

    /**
     * Sets the identifier of the market data value that provides the rate.
     * @param rateId  the new value, not null
     * @return this, for chaining, not null
     */
    public Builder rateId(ObservableId rateId) {
      JodaBeanUtils.notNull(rateId, "rateId");
      this.rateId = rateId;
      return this;
    }

    /**
     * Sets the additional spread added to the rate.
     * @param additionalSpread  the new value
     * @return this, for chaining, not null
     */
    public Builder additionalSpread(double additionalSpread) {
      this.additionalSpread = additionalSpread;
      return this;
    }

    /**
     * Sets the label to use for the node, defaulted.
     * <p>
     * When building, this will default based on the tenor if not specified.
     * @param label  the new value, not empty
     * @return this, for chaining, not null
     */
    public Builder label(String label) {
      JodaBeanUtils.notEmpty(label, "label");
      this.label = label;
      return this;
    }

    /**
     * Sets the method by which the date of the node is calculated, defaulted to 'End'.
     * @param date  the new value
     * @return this, for chaining, not null
     */
    public Builder date(CurveNodeDate date) {
      this.date = date;
      return this;
    }

    //-----------------------------------------------------------------------
    @Override
    public String toString() {
      StringBuilder buf = new StringBuilder(192);
      buf.append("OvernightIborSwapCurveNode.Builder{");
      buf.append("template").append('=').append(JodaBeanUtils.toString(template)).append(',').append(' ');
      buf.append("rateId").append('=').append(JodaBeanUtils.toString(rateId)).append(',').append(' ');
      buf.append("additionalSpread").append('=').append(JodaBeanUtils.toString(additionalSpread)).append(',').append(' ');
      buf.append("label").append('=').append(JodaBeanUtils.toString(label)).append(',').append(' ');
      buf.append("date").append('=').append(JodaBeanUtils.toString(date));
      buf.append('}');
      return buf.toString();
    }

  }

  ///CLOVER:ON
  //-------------------------- AUTOGENERATED END --------------------------
}