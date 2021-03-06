//    Openbravo POS is a point of sales application designed for touch screens.
//    Copyright (C) 2007-2009 Openbravo, S.L.
//    http://www.openbravo.com/product/pos
//
//    This file is part of Openbravo POS.
//
//    Openbravo POS is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    Openbravo POS is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with Openbravo POS.  If not, see <http://www.gnu.org/licenses/>.
package com.openbravo.pos.sales;

import com.openbravo.basic.BasicException;
import com.openbravo.beans.JCurrencyDialog;
import com.openbravo.beans.JNumberDialog;
import com.openbravo.beans.JPercentDialog;
import com.openbravo.data.gui.ComboBoxValModel;
import com.openbravo.data.gui.ListKeyed;
import com.openbravo.data.gui.MessageInf;
import com.openbravo.data.loader.SentenceList;
import com.openbravo.data.loader.Session;
import com.openbravo.format.Formats;
import com.openbravo.pos.customers.CustomerInfoExt;
import com.openbravo.pos.customers.DataLogicCustomers;
import com.openbravo.pos.customers.JCustomerFinder;
import com.openbravo.pos.forms.*;
import com.openbravo.pos.inventory.TaxCategoryInfo;
import com.openbravo.pos.panels.JProductFinder;
import com.openbravo.pos.payment.JPaymentSelect;
import com.openbravo.pos.payment.JPaymentSelectReceipt;
import com.openbravo.pos.payment.JPaymentSelectRefund;
import com.nordpos.device.ticket.TicketParser;
import com.nordpos.device.ticket.TicketPrinterException;
import com.openbravo.pos.promotion.DiscountMoney;
import com.openbravo.pos.promotion.DiscountPercent;
import com.nordpos.device.scale.ScaleException;
import com.openbravo.pos.scripting.ScriptEngine;
import com.openbravo.pos.scripting.ScriptException;
import com.openbravo.pos.scripting.ScriptFactory;
import com.openbravo.pos.ticket.ProductInfoExt;
import com.openbravo.pos.ticket.TaxInfo;
import com.openbravo.pos.ticket.TicketInfo;
import com.openbravo.pos.ticket.TicketLineInfo;
import com.openbravo.pos.util.JRPrinterAWT;
import com.openbravo.pos.util.ReportUtils;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.print.PrintService;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapArrayDataSource;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.xml.JRXmlLoader;


/**
 *
 * @author adrianromero
 * @author Andrey Svininykh <svininykh@gmail.com>
 * @author <dmg244@gmail.com>
 */
public abstract class JPanelTicket extends JPanel implements JPanelView, BeanFactoryApp, TicketsEditor {

    private static final String PRINTER_SHEMA = "/com/nordpos/templates/Schema.Printer.xsd";
    private static final String PRINT_TICKET_TOTAL = "/com/nordpos/templates/Printer.TicketTotal.xml";
    private static final String PRINT_TICKET = "/com/nordpos/templates/Printer.Ticket.xml";
    private static final String PRINT_TICKET_2 = "/com/nordpos/templates/Printer.Ticket2.xml";
    private static final String PRINT_TICKET_LINE = "/com/nordpos/templates/Printer.TicketLine.xml";
    
    // Variable numerica
    private final static int NUMBERZERO = 0;
    private final static int NUMBERVALID = 1;
    private final static int NUMBER_INPUTZERO = 0;
    private final static int NUMBER_INPUTZERODEC = 1;
    private final static int NUMBER_INPUTINT = 2;
    private final static int NUMBER_INPUTDEC = 3;
    private final static int NUMBER_PORZERO = 4;
    private final static int NUMBER_PORZERODEC = 5;
    private final static int NUMBER_PORINT = 6;
    private final static int NUMBER_PORDEC = 7;
    private int NUMBER_STATE = 0;
   
    private int MULTIPLY = 0;
    private static final int DEFAULT = 0;
    private static final int ACTIVE = 1;
    private static final int DECIMAL = 2;
    protected JTicketLines m_ticketlines;

    private TicketParser m_TTP;
    protected TicketInfo m_oTicket;
    protected Object m_oTicketExt;
    // Estas tres variables forman el estado...
    private int m_iNumberStatus;
    private int m_iNumberStatusInput;
    private int m_iNumberStatusPor;
    private StringBuffer m_sBarcode;
    
    
    private JTicketsBag m_ticketsbag;
    private SentenceList senttax;
    private ListKeyed taxcollection;

    private SentenceList senttaxcategories;

    private ComboBoxValModel taxcategoriesmodel;
    private TaxesLogic taxeslogic;

    protected JPanelButtons m_jbtnconfig;
    protected PropertiesConfig panelconfig;
    protected AppView m_App;
    protected DataLogicSystem dlSystem;
    protected DataLogicSales dlSales;
    protected DataLogicCustomers dlCustomers;
    private JPaymentSelect paymentdialogreceipt;
    private JPaymentSelect paymentdialogrefund;

    private Double m_dDiscountRate1;
    private Double m_dDiscountRate2;
    private Double m_dDiscountRate3;

    private Boolean bTypeDiscountRate;

    private Double m_dDiscountMoney1;
    private Double m_dDiscountMoney2;
    private Double m_dDiscountMoney3;
    private Boolean bTypeDiscountMoney;
    

    private static final Logger logger = Logger.getLogger(JPanelTicket.class.getName());

    private boolean isMultiplyControl;

    public JPanelTicket() {
        initComponents();
    }

    @Override
    public void init(AppView app) throws BeanFactoryException {
        //m_jbtnDiscountRate.setText(AppLocal.getIntString("button.ticketdiscount"));
        m_App = app;
        dlSystem = (DataLogicSystem) m_App.getBean(DataLogicSystem.class.getName());
        dlSales = (DataLogicSales) m_App.getBean(DataLogicSales.class.getName());
        dlCustomers = (DataLogicCustomers) m_App.getBean(DataLogicCustomers.class.getName());

        // borramos el boton de bascula si no hay bascula conectada
        m_jbtnScale.setVisible(m_App.getDeviceScale().existsScale());

        m_ticketsbag = getJTicketsBag();
        m_jPanelBag.add(m_ticketsbag.getBagComponent(), BorderLayout.LINE_START);
        add(m_ticketsbag.getNullComponent(), "null");

        m_ticketlines = new JTicketLines(dlSystem.getResourceAsXML("Ticket.Line"));
        m_jPanelCentral.add(m_ticketlines, java.awt.BorderLayout.CENTER);

        m_ticketlines.addListSelectionListener(new CatalogSelectionListener());

        // Los botones configurables...
        String sConfigRes = dlSystem.getResourceAsXML("Ticket.Buttons");
        m_jbtnconfig = new JPanelButtons(sConfigRes, this);
        panelconfig = new PropertiesConfig(sConfigRes);
        m_jButtonsExt.add(m_jbtnconfig);

        // El panel de los productos o de las lineas...
        if ("false".equals(panelconfig.getProperty("catvisible")) == false) {
            //catcontainer.add(getSouthComponent(), BorderLayout.CENTER);
            //m_jImage.setVisible(false);
        } else {
            //m_jImage.setVisible(true);
        }
        /*
        bTypeDiscountRate = false;
        m_jbtnDiscountRate.setText(AppLocal.getIntString("button.ticketdiscount"));

        if ("true".equals(panelconfig.getProperty("discount-rate-visible", "true")) || "true".equals(panelconfig.getProperty("discountvisible"))) {
            m_jDiscountRatePanel.setVisible(true);
        } else {
            m_jDiscountRatePanel.setVisible(false);
        }
       
        m_dDiscountRate1 = Double.parseDouble(panelconfig.getProperty("discountrate-1", "5")) / 100;
        m_dDiscountRate2 = Double.parseDouble(panelconfig.getProperty("discountrate-2", "10")) / 100;
        m_dDiscountRate3 = Double.parseDouble(panelconfig.getProperty("discountrate-3", "15")) / 100;

        bTypeDiscountMoney = true;

        m_jbtnDiscountMoney.setText(AppLocal.getIntString("button.rowdiscount"));

        if ("true".equals(panelconfig.getProperty("discount-money-visible", "false")) || "true".equals(panelconfig.getProperty("discountvisible"))) {
            m_jDiscountMoneyPanel.setVisible(true);
        } else {
            m_jDiscountMoneyPanel.setVisible(false);
        }

        m_dDiscountMoney1 = Double.parseDouble(panelconfig.getProperty("discountmoney-1", "1"));
        m_dDiscountMoney2 = Double.parseDouble(panelconfig.getProperty("discountmoney-2", "5"));
        m_dDiscountMoney3 = Double.parseDouble(panelconfig.getProperty("discountmoney-3", "10")); */

        // El modelo de impuestos
        senttax = dlSales.getTaxList();
        senttaxcategories = dlSales.getTaxCategoriesList();

        taxcategoriesmodel = new ComboBoxValModel();

        // ponemos a cero el estado
        stateToZero();

        // inicializamos
        m_oTicket = null;
        m_oTicketExt = null;

        isMultiplyControl = "true".equals(panelconfig.getProperty("refmultcontrol", "false"));

    }

    @Override
    public Object getBean() {
        return this;
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void activate() throws BasicException {

        paymentdialogreceipt = JPaymentSelectReceipt.getDialog(this);
        paymentdialogreceipt.init(m_App);
        paymentdialogrefund = JPaymentSelectRefund.getDialog(this);
        paymentdialogrefund.init(m_App);

        // impuestos incluidos seleccionado ?
        m_jaddtax.setSelected("true".equals(panelconfig.getProperty("taxesincluded")));

        // Inicializamos el combo de los impuestos.
        java.util.List<TaxInfo> taxlist = senttax.list();
        taxcollection = new ListKeyed<>(taxlist);
        java.util.List<TaxCategoryInfo> taxcategorieslist = senttaxcategories.list();

        taxcategoriesmodel = new ComboBoxValModel(taxcategorieslist);
        m_jTax.setModel(taxcategoriesmodel);

//        String taxesid = panelconfig.getProperty("taxcategoryid");
        String taxesid = m_App.getDefaultTaxCategory();

        if (taxesid == null) {
            if (m_jTax.getItemCount() > 0) {
                m_jTax.setSelectedIndex(0);
            }
        } else {
            taxcategoriesmodel.setSelectedKey(taxesid);
        }

        taxeslogic = new TaxesLogic(taxlist);

        // Show taxes options
        if (m_App.getAppUserView().getUser().hasPermission("sales.ChangeTaxOptions")) {
            m_jTax.setVisible(true);
            m_jaddtax.setVisible(true);
        } else {
            m_jTax.setVisible(false);
            m_jaddtax.setVisible(false);
        }

        // Authorization for buttons
        btnSplit.setEnabled(m_App.getAppUserView().getUser().hasPermission("sales.Total"));
        m_jEditLine.setEnabled(m_App.getAppUserView().getUser().hasPermission("sales.EditLines"));
        m_jDelete.setEnabled(m_App.getAppUserView().getUser().hasPermission("sales.EditLines"));
        m_jNumberKeys.setMinusEnabled(m_App.getAppUserView().getUser().hasPermission("sales.EditLines"));
        m_jNumberKeys.setEqualsEnabled(m_App.getAppUserView().getUser().hasPermission("sales.Total"));
        m_jbtnconfig.setPermissions(m_App.getAppUserView().getUser());

        // Permiss??es para desconto
        /*
        m_jDiscount1.setEnabled(m_App.getAppUserView().getUser().hasPermission("sales.Discount"));
        m_jDiscount2.setEnabled(m_App.getAppUserView().getUser().hasPermission("sales.Discount"));
        m_jDiscount3.setEnabled(m_App.getAppUserView().getUser().hasPermission("sales.Discount"));
        m_jKeypadDiscountRate.setEnabled(m_App.getAppUserView().getUser().hasPermission("sales.DiscountMulti"));
        //m_jbtnDiscount.setEnabled(m_App.getAppUserView().getUser().hasPermission("sales.Discount") || m_App.getAppUserView().getUser().hasPermission("sales.DiscountMulti"));
        */
        // Valores para descontos
        //m_jDiscount1.setText(Formats.PERCENT.formatValue(m_dDiscountRate1));
        //m_jDiscount2.setText(Formats.PERCENT.formatValue(m_dDiscountRate2));
        //m_jDiscount3.setText(Formats.PERCENT.formatValue(m_dDiscountRate3));
        //m_jDisableDiscountRate.setText(Formats.PERCENT.formatValue(0.0));

        //m_jDiscount4.setEnabled(m_App.getAppUserView().getUser().hasPermission("sales.Discount"));
        //m_jDiscount5.setEnabled(m_App.getAppUserView().getUser().hasPermission("sales.Discount"));
        //m_jDiscount6.setEnabled(m_App.getAppUserView().getUser().hasPermission("sales.Discount"));
        
       // m_jKeypadDiscountMoney.setEnabled(m_App.getAppUserView().getUser().hasPermission("sales.DiscountMulti"));

        //m_jDiscount4.setText(Formats.CURRENCY.formatValue(m_dDiscountMoney1));
        //m_jDiscount5.setText(Formats.CURRENCY.formatValue(m_dDiscountMoney2));
        //m_jDiscount6.setText(Formats.CURRENCY.formatValue(m_dDiscountMoney3));
        //m_jDisableDiscountMoney.setText(Formats.CURRENCY.formatValue(0.0));

        m_ticketsbag.activate();
    }

    @Override
    public boolean deactivate() {

        return m_ticketsbag.deactivate();
    }

    protected abstract JTicketsBag getJTicketsBag();

    protected abstract Component getSouthComponent();

    protected abstract void resetSouthComponent();

    @Override
    public void setActiveTicket(TicketInfo oTicket, Object oTicketExt) {

        m_oTicket = oTicket;
        m_oTicketExt = oTicketExt;

        if (m_oTicket != null) {
            // Asign preeliminary properties to the receipt
            m_oTicket.setUser(m_App.getAppUserView().getUser().getUserInfo());
            m_oTicket.setActiveCash(m_App.getActiveCashIndex());
            m_oTicket.setDate(new Date()); // Set the edition date.
        }

        executeEvent(m_oTicket, m_oTicketExt, "ticket.show");

        refreshTicket();
    }

    @Override
    public TicketInfo getActiveTicket() {
        return m_oTicket;
    }

    private void refreshTicket() {

        CardLayout cl = (CardLayout) (getLayout());

        if (m_oTicket == null) {
            m_jTicketId.setText(null);
            m_ticketlines.clearTicketLines();

            m_jSubtotalEuros.setText(null);
            m_jTaxesEuros.setText(null);
            m_jTotalEuros.setText(null);

            stateToZero();

            // Muestro el panel de nulos.
            cl.show(this, "null");
            resetSouthComponent();

        } else {
            if (isMultiplyControl) {
                if (m_oTicket.getTicketType() == TicketInfo.RECEIPT_REFUND) {
                    //Make disable Search and Edit Buttons and other
                    m_jDelete.setVisible(false);
                    m_jEditLine.setVisible(false);
                    m_jList.setVisible(false);
                    jEditAttributes.setVisible(false);
                  //  m_jDiscountRatePanel.setVisible(false);
                  //  m_jDiscountMoneyPanel.setVisible(false);
                } else {
                    m_jDelete.setVisible(true);
                    m_jEditLine.setVisible(true);
                    m_jList.setVisible(true);
                    jEditAttributes.setVisible(true);
                }
            }

            // Refresh ticket taxes
            for (TicketLineInfo line : m_oTicket.getLines()) {
                line.setTaxInfo(taxeslogic.getTaxInfo(line.getProductTaxCategoryID(), m_oTicket.getDate(), m_oTicket.getCustomer()));
            }

            // The ticket name
            m_jTicketId.setText(m_oTicket.getName(m_oTicketExt));

            // Limpiamos todas las filas y anadimos las del ticket actual
            m_ticketlines.clearTicketLines();

            for (int i = 0; i < m_oTicket.getLinesCount(); i++) {
                m_ticketlines.addTicketLine(m_oTicket.getLine(i));
            }
            printPartialTotals();
            stateToZero();

            // Muestro el panel de tickets.
            cl.show(this, "ticket");
            resetSouthComponent();

            // activo el tecleador...
            m_jKeyFactory.setText(null);
            java.awt.EventQueue.invokeLater(new Runnable() {

                @Override
                public void run() {
                    m_jKeyFactory.requestFocus();
                }
            });
        }
    }

    private void printPartialTotals() {
            m_jSubtotalEuros.setText(m_oTicket.printSubTotal());
            m_jTaxesEuros.setText(m_oTicket.printTax());
            m_jTotalEuros.setText(m_oTicket.printTotal());
    }

    private void paintTicketLine(int index, TicketLineInfo oLine) {

        if (executeEventAndRefresh("ticket.setline", new ScriptArg("index", index), new ScriptArg("line", oLine)) == null) {

            m_oTicket.setLine(index, oLine);
            m_ticketlines.setTicketLine(index, oLine);
            m_ticketlines.setSelectedIndex(index);

            visorTicketLine(oLine); // Y al visor tambien...
            printPartialTotals();
            stateToZero();

            // event receipt
            executeEventAndRefresh("ticket.change");
        }
    }

    private void addTicketLine(ProductInfoExt oProduct, double dMul, double dPrice, double cprice) {
        TaxInfo tax = taxeslogic.getTaxInfo(oProduct.getTaxCategoryID(), m_oTicket.getDate(), m_oTicket.getCustomer());
        addTicketLine(new TicketLineInfo(oProduct, dMul, dPrice,cprice,tax, (java.util.Properties) (oProduct.getProperties().clone())));
    }

    protected void addTicketLine(TicketLineInfo oLine) {

        if (executeEventAndRefresh("ticket.addline", new ScriptArg("line", oLine)) == null) {

            if (oLine.isProductCom()) {
                // Comentario entonces donde se pueda
                int i = m_ticketlines.getSelectedIndex();

                // me salto el primer producto normal...
                if (i >= 0 && !m_oTicket.getLine(i).isProductCom()) {
                    i++;
                }

                // me salto todos los productos auxiliares...
                while (i >= 0 && i < m_oTicket.getLinesCount() && m_oTicket.getLine(i).isProductCom()) {
                    i++;
                }

                if (i >= 0) {
                    m_oTicket.insertLine(i, oLine);
                    m_ticketlines.insertTicketLine(i, oLine); // Pintamos la linea en la vista...
                } else {
                    Toolkit.getDefaultToolkit().beep();
                }
            } else {
                // Producto normal, entonces al finalnewline.getMultiply()
                m_oTicket.addLine(oLine);
                m_ticketlines.addTicketLine(oLine); // Pintamos la linea en la vista...
            }

            //visorTicketLine(oLine);
            printPartialTotals();
            stateToZero();

            if ("true".equals(panelconfig.getProperty("attributesautoset")) == true) {
                // ?????????????????? ???????? ?????????? ??????????????????????????, ???????? ?????? ??????????????????????????
                int i = m_ticketlines.getSelectedIndex();
                try {
                    TicketLineInfo line = m_oTicket.getLine(i);
                    JProductAttEdit attedit = JProductAttEdit.getAttributesEditor(this, m_App.getSession());
                    attedit.editAttributes(line.getProductAttSetId(), line.getProductAttSetInstId());
                    attedit.setVisible(true);
                    if (attedit.isOK()) {
                        line.setProductAttSetInstId(attedit.getAttributeSetInst());
                        line.setProductAttSetInstDesc(attedit.getAttributeSetInstDescription());
                        paintTicketLine(i, line);
                    } else {
                        // ???????? ???????????? ???????????? ????????????, ???????? ??????????????????????, ???????????? ?? ?????????????? ?????????????? ??????????????????
                        removeTicketLine(i);
                    }
                } catch (BasicException ex) {
                    // ?????? ?????????? ?????????????????? ??????, ???????? ?? ???????????? ???? ?????????????????????? ????????????????????????????
                }
            }

            if ("true".equals(panelconfig.getProperty("price-is-zero")) == true) {
                if (oLine.getPrice() == 0.0) {
                    int i = m_ticketlines.getSelectedIndex();
                    Double dPriceSet = JCurrencyDialog.showEditNumber(this, AppLocal.getIntString("message.setprice"));

                    if (dPriceSet == null) {
                        removeTicketLine(i);
                    } else {
                        oLine.setPrice(dPriceSet);
                        paintTicketLine(i, oLine);
                    }
                }
            }

            // event receipt
            executeEventAndRefresh("ticket.change");
        }
    }

    protected void removeTicketLine(int i) {

        if (executeEventAndRefresh("ticket.removeline", new ScriptArg("index", i)) == null) {

            if (m_oTicket.getLine(i).isProductCom()) {
                // Es un producto auxiliar, lo borro y santas pascuas.
                m_oTicket.removeLine(i);
                m_ticketlines.removeTicketLine(i);
            } else {
                // Es un producto normal, lo borro.
                m_oTicket.removeLine(i);
                m_ticketlines.removeTicketLine(i);
                // Y todos lo auxiliaries que hubiera debajo.
                while (i < m_oTicket.getLinesCount() && m_oTicket.getLine(i).isProductCom()) {
                    m_oTicket.removeLine(i);
                    m_ticketlines.removeTicketLine(i);
                }
            }

            //visorTicketLine(null); // borro el visor
            printPartialTotals(); // pinto los totales parciales...
            stateToZero(); // Pongo a cero

            // event receipt
            executeEventAndRefresh("ticket.change");

        }
    }

    private ProductInfoExt getInputProduct() {
        ProductInfoExt oProduct = new ProductInfoExt(); // Es un ticket
        oProduct.setReference(null);
        oProduct.setCode(null);
        oProduct.setName("");
        oProduct.setTaxCategoryID(((TaxCategoryInfo) taxcategoriesmodel.getSelectedItem()).getID());

        oProduct.setPriceSell(includeTaxes(oProduct.getTaxCategoryID(), getInputValue()));

        return oProduct;
    }

    private double includeTaxes(String tcid, double dValue) {
        if (m_jaddtax.isSelected()) {
            TaxInfo tax = taxeslogic.getTaxInfo(tcid, m_oTicket.getDate(), m_oTicket.getCustomer());
            double dTaxRate = tax == null ? 0.0 : tax.getRate();
            return dValue / (1.0 + dTaxRate);
        } else {
            return dValue;
        }
    }

    private double getInputValue() {
        try {
            return Double.parseDouble(m_jPrice.getText());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private double getPorValue() {
        try {
            return Double.parseDouble(m_jPor.getText().substring(1));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return 1.0;
        }
    }

    private void stateToZero() 
    {
        m_jPor.setText("");
        m_jPrice.setText("");
        
        String clear = "";
        m_sBarcode = new StringBuffer(clear);
 
        m_iNumberStatus = NUMBER_INPUTZERO;
        m_iNumberStatusInput = NUMBERZERO;
        m_iNumberStatusPor = NUMBERZERO;
    }

     private void incProductByCode(String sCode) {
        incProductByCodeUnit(sCode, 1.0);
    }
    
    private void incProductByCodePrice(String sCode, double dPriceSell, double costprice) {
        // precondicion: sCode != null

        try {
            ProductInfoExt oProduct = dlSales.getProductInfoByCode(sCode);
            if (oProduct == null) {
                Toolkit.getDefaultToolkit().beep();
                new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.noproduct")).show(this);
                stateToZero();
            } else {
                // Se anade directamente una unidad con el precio y todo
                if (m_jaddtax.isSelected()) {
                    // debemos quitarle los impuestos ya que el precio es con iva incluido...
                    TaxInfo tax = taxeslogic.getTaxInfo(oProduct.getTaxCategoryID(), m_oTicket.getDate(), m_oTicket.getCustomer());
                    addTicketLine(oProduct, 1.0, dPriceSell / (1.0 + tax.getRate()), costprice);
                } else {
                    addTicketLine(oProduct, 1.0, dPriceSell,costprice);
                }
            }
        } catch (BasicException eData) {
            stateToZero();
            new MessageInf(eData).show(this);
        }
    }

    private void incProductByCodeUnit(String sCode, double dUnitSell) {
        try {
            ProductInfoExt oProduct = dlSales.getProductInfoByCode(sCode);
            if (oProduct == null) {
                Toolkit.getDefaultToolkit().beep();

                new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.noproduct")).show(this);
                stateToZero();
            } else {
                if (m_jaddtax.isSelected()) {
                    TaxInfo tax = taxeslogic.getTaxInfo(oProduct.getTaxCategoryID(), m_oTicket.getDate(), m_oTicket.getCustomer());
                    addTicketLine(oProduct, dUnitSell, oProduct.getPriceSellTax(tax) / (1.0 + tax.getRate()),oProduct.getPriceBuy());
                } else {
                    addTicketLine(oProduct, dUnitSell, oProduct.getPriceSell(), oProduct.getPriceBuy());
                }
            }
        } catch (BasicException eData) {
            stateToZero();
            new MessageInf(eData).show(this);
        }
    }

    private void incProduct(ProductInfoExt prod) {

        if (prod.isScale() && m_App.getDeviceScale().existsScale()) {
            try {
                Double value = m_App.getDeviceScale().readWeight();
                if (value != null) {
                    incProduct(value, prod);
                }
            } catch (ScaleException e) {
                Toolkit.getDefaultToolkit().beep();
                new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.noweight"), e).show(this);
                stateToZero();
            }
        } else {
            // No es un producto que se pese o no hay balanza
            incProduct(1.0, prod);
        }
    }

    private void incProduct(double dPor, ProductInfoExt prod) {
        // precondicion: prod != null
        addTicketLine(prod, dPor, prod.getPriceSell(),prod.getPriceBuy());
    }

    protected void buttonTransition(ProductInfoExt prod) {
        // precondicion: prod != null

        if (m_iNumberStatusInput == NUMBERZERO && m_iNumberStatusPor == NUMBERZERO) {
            incProduct(prod);
        } else if (m_iNumberStatusInput == NUMBERVALID && m_iNumberStatusPor == NUMBERZERO) {
            incProduct(getInputValue(), prod);
        } else {
            Toolkit.getDefaultToolkit().beep();
        }
    }
    private void deleteLine(int index) {
        if (index < 0) {
            Toolkit.getDefaultToolkit().beep(); // No hay ninguna seleccionada
        } else {
            removeTicketLine(index);
        }
    }
    private void addUnits(double dUnits) {
        int i = m_ticketlines.getSelectedIndex();
        if (i < 0) {
             Toolkit.getDefaultToolkit().beep();
         }
        else{
            TicketLineInfo newline = new TicketLineInfo(m_oTicket.getLine(i));
            double dunits = newline.getMultiply() + dUnits;
            
            if (dunits <= 0.0) {
                deleteLine(i);
            } else {
                newline.setMultiply(newline.getMultiply() + dUnits);
                paintTicketLine(i, newline);
            }
        }
    }

    private void setUnits(double dUnits) {
        int i = m_ticketlines.getSelectedIndex();
        if (i >= 0) {
           // m_ticketlines inv = m_invlines.getLine(i);
            //inv.setMultiply(dUnits);
            //m_invlines.setLine(i, inv);
        }
    }
    
 private void stateTransition(char cTrans) {

        try
        { 
           switch (cTrans) {
                case '\u007f':
                    m_jPrice.setText("");
                    NUMBER_STATE = DEFAULT;
                    break;
                case '*':
                    MULTIPLY = ACTIVE;
                    break;
                case '+':
                    if (MULTIPLY != DEFAULT && NUMBER_STATE != DEFAULT) {
                        setUnits(Double.parseDouble(m_jPrice.getText()));
                        m_jPrice.setText("");
                        
                    } else {
                        if (m_jPrice.getText().isEmpty()) {
                            addUnits(1.0);
                        } else {
                            addUnits(Double.parseDouble(m_jPrice.getText()));
                            m_jPrice.setText("");
                        }
                    }       NUMBER_STATE = DEFAULT;
                    MULTIPLY = DEFAULT;
                    NUMBER_STATE = DEFAULT;
                    MULTIPLY = DEFAULT;
                    break;
                case '-':
                    if (m_jPrice.getText() == null || m_jPrice.getText().equals("")) {
                            addUnits(-1.0);
                    } else {
                            addUnits(-Double.parseDouble(m_jPrice.getText()));
                            m_jPrice.setText("");
                        
                    }
                    NUMBER_STATE = DEFAULT;
                    MULTIPLY = DEFAULT;
                    break;
                case '.':
                     if (m_jPrice.getText() == null || m_jPrice.getText().equals("")) {
                            m_jPrice.setText("0.");
                    } else if (NUMBER_STATE != DECIMAL) {
                        m_jPrice.setText(m_jPrice.getText() + cTrans);
                    }
                    NUMBER_STATE = DECIMAL;
                     break;
                case '=':
                case ' ':
                   if (m_oTicket.getLinesCount() > 0) {

                        try {
                                if (closeTicket(m_oTicket, m_oTicketExt)) {
                                // Ends edition of current receipt
                                    m_ticketsbag.deleteTicket();
                                } else {
                                // repaint current ticket
                                    refreshTicket();
                                }
                            } 
                        catch (BasicException ex) {
                            Logger.getLogger(JPanelTicket.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    } else {
                        Toolkit.getDefaultToolkit().beep();
                    } 
                  break;
                case '0':   
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    m_jPrice.setText(m_jPrice.getText() + cTrans);
                    if (NUMBER_STATE != DECIMAL) {
                        NUMBER_STATE = ACTIVE;
                    }
                   break;
                case '\n':
                    String sCode = m_jPrice.getText();
                    incProductByCode(sCode);
                    m_jPrice.setText("");
                   break;
                default:
                    Toolkit.getDefaultToolkit().beep();
                    break;
            }
        }
        catch(NumberFormatException eData) {
            new MessageInf(eData).show(this);
        }
        
    }
    
    private boolean closeTicket(TicketInfo ticket, Object ticketext) throws BasicException {

        boolean resultok = false;

        if (m_App.getAppUserView().getUser().hasPermission("sales.Total")) {

            try {
                // reset the payment info
                taxeslogic.calculateTaxes(ticket);
                if (ticket.getTotal() >= 0.0) {
                    ticket.resetPayments(); //Only reset if is sale
                }

                if (executeEvent(ticket, ticketext, "ticket.total") == null) {

                    // Muestro el total
                    try {
                        printTicket(PRINT_TICKET_TOTAL, ticket, ticketext);
                    } catch (TicketPrinterException e) {
                    }

                    // Select the Payments information
                    JPaymentSelect paymentdialog = ticket.getTicketType() == TicketInfo.RECEIPT_NORMAL
                            ? paymentdialogreceipt
                            : paymentdialogrefund;
                    paymentdialog.setPrintSelected("true".equals(panelconfig.getProperty("printselected", "true")));

                    paymentdialog.setTransactionID(ticket.getTransactionID());

                    CustomerInfoExt customer = ticket.getCustomer();
                    if (customer != null) {
                        dlSales.loadCustomerExt(ticket.getCustomer().getId());
                    }
                    
                    if (paymentdialog.showDialog(ticket.getTotal(), customer)) {

                        // assign the payments selected and calculate taxes.
                        ticket.setPayments(paymentdialog.getSelectedPayments());

                        // Asigno los valores definitivos del ticket...
                        ticket.setUser(m_App.getAppUserView().getUser().getUserInfo()); // El usuario que lo cobra
                        ticket.setActiveCash(m_App.getActiveCashIndex());
                        ticket.setDate(new Date()); // Le pongo la fecha de cobro

                        if (executeEvent(ticket, ticketext, "ticket.save") == null) {
                            Session s = m_App.getSession();
                            try {
                                // Start transaction
                                if (!s.isTransaction()) {
                                    s.begin();
                                }

                                // Process close ticket
                                try {
                                    // Save the receipt and assign a receipt number                                    
                                    dlSales.saveTicket(ticket, m_App.getInventoryLocation());

                                    // Execute ticket.close event
                                    executeEvent(ticket, ticketext, "ticket.close", new ScriptArg("print", paymentdialog.isPrintSelected()));

                                    // Print receipt
                                    printTicket(paymentdialog.isPrintSelected()
                                            ? PRINT_TICKET
                                            : PRINT_TICKET_2, ticket, ticketext);

                                    s.commit();
                                    resultok = true;

                                } catch (BasicException eData) {
                                    //MessageInf msg = new MessageInf(MessageInf.SGN_NOTICE, AppLocal.getIntString("message.nosaveticket"), eData);
                                    //msg.show(this);
                                } catch (TicketPrinterException e) {
                                    logger.finer("TicketFiscalPrinterException occured, rollback changes");
                                    s.rollback();
                                    resultok = false;
                                } catch (SQLException e) {
                                    // XXX: Additional checks. Did executeEvent() goes throw some exceptions? Or is process it correctly?
                                    logger.log(Level.SEVERE, "Error occured while executing ticket.close event, rollback transaction", e);
                                    s.rollback();
                                    resultok = false;
                                }

                            } catch (java.sql.SQLException e) {
                                logger.log(Level.SEVERE, "SQL error accured while process closing ticket", e);
                                try {
                                    s.rollback();
                                } catch (java.sql.SQLException rollbackException) {
                                }
                                resultok = false;
                            }
                        }
                    }
                }

            } catch (TaxesException e) {
                MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotcalculatetaxes"));
                msg.show(this);
                resultok = false;
            }

            // reset the payment info
            m_oTicket.resetTaxes();
            m_oTicket.resetPayments();
        }

        // cancelled the ticket.total script
        // or canceled the payment dialog
        // or canceled the ticket.close script
        return resultok;
    }

    private void printTicket(String sresourcename, TicketInfo ticket, Object ticketext)
            throws TicketPrinterException {

        InputStream schema = getClass().getResourceAsStream(PRINTER_SHEMA);
        InputStream template = getClass().getResourceAsStream(sresourcename);
        if (schema == null || template == null) {
           // MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotprintticket"));
           // msg.show(JPanelTicket.this);
        } else {
            m_TTP = new TicketParser(schema, m_App.getDeviceTicket());
            try {
                ScriptEngine script = ScriptFactory.getScriptEngine(ScriptFactory.VELOCITY);
                script.put("taxes", taxcollection);
                script.put("taxeslogic", taxeslogic);
                script.put("ticket", ticket);
                script.put("place", ticketext);
                script.put("local", new AppLocal());
                m_TTP.printTicket(template, script);
            } catch (ScriptException e) {
                MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotprintticket"), e);
                msg.show(JPanelTicket.this);
            } catch (TicketPrinterException e) {
                MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotprintticket"), e);
                msg.show(JPanelTicket.this);
                throw e;
            }
        }
    }

    private void printReport(String resourcefile, TicketInfo ticket, Object ticketext) {

        try {

            JasperReport jr;

            InputStream in = getClass().getResourceAsStream(resourcefile + ".ser");
            if (in == null) {
                // read and compile the report
                JasperDesign jd = JRXmlLoader.load(getClass().getResourceAsStream(resourcefile + ".jrxml"));
                jr = JasperCompileManager.compileReport(jd);
            } else {
                // read the compiled reporte
                try (ObjectInputStream oin = new ObjectInputStream(in)) {
                    jr = (JasperReport) oin.readObject();
                }
            }

            // Construyo el mapa de los parametros.
            Map reportparams = new HashMap();
            // reportparams.put("ARG", params);
            try {
                reportparams.put("REPORT_RESOURCE_BUNDLE", ResourceBundle.getBundle(resourcefile + ".properties"));
            } catch (MissingResourceException e) {
            }
            reportparams.put("TAXESLOGIC", taxeslogic);

            Map reportfields = new HashMap();
            reportfields.put("TICKET", ticket);
            reportfields.put("PLACE", ticketext);

            JasperPrint jp = JasperFillManager.fillReport(jr, reportparams, new JRMapArrayDataSource(new Object[]{reportfields}));

            PrintService service = ReportUtils.getPrintService(m_App.getProperties().getProperty("machine.printername"));

            JRPrinterAWT.printPages(jp, 0, jp.getPages().size() - 1, service);

        } catch (JRException | IOException | ClassNotFoundException e) {
            MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotloadreport"), e);
            msg.show(this);
        }
    }

    private void visorTicketLine(TicketLineInfo oLine) {
        try{
            if (oLine == null) {
                m_App.getDeviceTicket().getDeviceDisplay().clearVisor();
            } else {
                m_TTP = new TicketParser(getClass().getResourceAsStream(PRINTER_SHEMA), m_App.getDeviceTicket());
                try {
                    ScriptEngine script = ScriptFactory.getScriptEngine(ScriptFactory.VELOCITY);
                    script.put("ticketline", oLine);
                    script.put("local", new AppLocal());
                    m_TTP.printTicket(getClass().getResourceAsStream(PRINT_TICKET_LINE), script);
                } catch (ScriptException | TicketPrinterException e) {
                    //MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotprintline"), e);
                    //msg.show(JPanelTicket.this);
                }
            }
        }
        catch(Exception e)
        {
        }
    }

    private Object evalScript(ScriptObject scr, String resource, ScriptArg... args) {

        // resource here is guaratied to be not null
        try {
            scr.setSelectedIndex(m_ticketlines.getSelectedIndex());
            return scr.evalScript(dlSystem.getResourceAsXML(resource), args);
        } catch (ScriptException e) {
            MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotexecute"), e);
            msg.show(this);
            return msg;
        }
    }

    public void evalScriptAndRefresh(String resource, ScriptArg... args) {
        // this method is intended to be called only from JPanelButtons.

        if (resource == null) {
            MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotexecute"));
            msg.show(this);
        } else {
            try {
                // calculate taxes
                taxeslogic.calculateTaxes(m_oTicket);
                // execute script
                ScriptObject scr = new ScriptObject(m_oTicket, m_oTicketExt);
                scr.setSelectedIndex(m_ticketlines.getSelectedIndex());
                evalScript(scr, resource, args);
                refreshTicket();
                setSelectedIndex(scr.getSelectedIndex());
            } catch (TaxesException e) {
                MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotcalculatetaxes"));
                msg.show(this);
            }
        }
    }

    public void printTicket(String resource) throws TicketPrinterException {
        // this method is intended to be called only from JPanelButtons.

        if (resource == null) {
            MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotexecute"));
            msg.show(this);
        } else {
            try {
                // calculate taxes
                taxeslogic.calculateTaxes(m_oTicket);
                printTicket(resource, m_oTicket, m_oTicketExt);
            } catch (TaxesException e) {
                MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotcalculatetaxes"));
                msg.show(this);
            }
        }
    }

    private Object executeEventAndRefresh(String eventkey, ScriptArg... args) {

        String resource = m_jbtnconfig.getEvent(eventkey);
        if (resource == null) {
            return null;
        } else {
            ScriptObject scr = new ScriptObject(m_oTicket, m_oTicketExt);
            scr.setSelectedIndex(m_ticketlines.getSelectedIndex());
            Object result = evalScript(scr, resource, args);
            refreshTicket();
            setSelectedIndex(scr.getSelectedIndex());
            return result;
        }
    }

    private Object executeEvent(TicketInfo ticket, Object ticketext, String eventkey, ScriptArg... args) {

        String resource = m_jbtnconfig.getEvent(eventkey);
        if (resource == null) {
            return null;
        } else {
            ScriptObject scr = new ScriptObject(ticket, ticketext);
            return evalScript(scr, resource, args);
        }
    }

    public BufferedImage getResourceAsImage(String sresourcename) {
        return DataLogicSystem.getResourceAsImage(sresourcename);
    }

    private void setSelectedIndex(int i) {

        if (i >= 0 && i < m_oTicket.getLinesCount()) {
            m_ticketlines.setSelectedIndex(i);
        } else if (m_oTicket.getLinesCount() > 0) {
            m_ticketlines.setSelectedIndex(m_oTicket.getLinesCount() - 1);
        }
    }

    public static class ScriptArg {

        private final String key;
        private final Object value;

        public ScriptArg(String key, Object value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        public Object getValue() {
            return value;
        }
    }

    public class ScriptObject {

        private final TicketInfo ticket;
        private final Object ticketext;
        private int selectedindex;

        private ScriptObject(TicketInfo ticket, Object ticketext) {
            this.ticket = ticket;
            this.ticketext = ticketext;
        }

        public double getInputValue() {
            if (m_iNumberStatusInput == NUMBERVALID && m_iNumberStatusPor == NUMBERZERO) {
                return JPanelTicket.this.getInputValue();
            } else {
                return 0.0;
            }
        }

        public int getSelectedIndex() {
            return selectedindex;
        }

        public void setSelectedIndex(int i) {
            selectedindex = i;
        }

        public void printReport(String resourcefile) {
            JPanelTicket.this.printReport(resourcefile, ticket, ticketext);
        }

        public void printTicket(String sresourcename) throws TicketPrinterException {
            JPanelTicket.this.printTicket(sresourcename, ticket, ticketext);
        }

        public Object evalScript(String code, ScriptArg... args) throws ScriptException {

            ScriptEngine script = ScriptFactory.getScriptEngine(ScriptFactory.BEANSHELL);

            script.put("hostname", m_App.getProperties().getProperty("machine.hostname"));
            script.put("ticket", ticket);
            script.put("place", ticketext);
            script.put("taxes", taxcollection);
            script.put("taxeslogic", taxeslogic);
            script.put("user", m_App.getAppUserView().getUser());
            script.put("sales", this);
            script.put("logicsales", dlSales);
            script.put("logicsystem", dlSystem);

            // more arguments
            for (ScriptArg arg : args) {
                script.put(arg.getKey(), arg.getValue());
            }

            return script.eval(code);
        }
    }

    private class CatalogSelectionListener implements ListSelectionListener {

        @Override
        public void valueChanged(ListSelectionEvent e) {
            if (!e.getValueIsAdjusting()) {
                int i = m_ticketlines.getSelectedIndex();

                if (i >= 0) {
                    try {
                        String sProduct = m_oTicket.getLine(i).getProductID();
                        if (sProduct != null) {
                            ProductInfoExt prod = JPanelTicket.this.dlSales.getProductInfo(sProduct);
                            if (prod.getImage() != null) {
                                //m_jImage.setImage(prod.getImage());
                            } else {
                                //m_jImage.setImage(null);
                            }
                        }
                    } catch (BasicException ex) {
                        Logger.getLogger(JPanelTicket.class.getName()).log(Level.SEVERE, null, ex);
                    }
                } else {
                    //m_jImage.setImage(null);
                }
            }
        }
    }

    private void performDiscountRate(Double discountrate) {
        int index = m_ticketlines.getSelectedIndex();
        double total = m_oTicket.getTotal();
        TicketLineInfo oLine;

        if (bTypeDiscountRate == true) {
            if (index >= 0) {
                oLine = new DiscountPercent().LineDiscountPercent(m_oTicket.getLine(index), 0.0);
                paintTicketLine(index, new DiscountPercent().LineDiscountPercent(oLine, discountrate));
            } else {
                Toolkit.getDefaultToolkit().beep();
            }
        } else if (bTypeDiscountRate == false) {
            if (total > 0.0) {
                for (int i = 0; i < m_oTicket.getLinesCount(); i++) {
                    oLine = new DiscountPercent().LineDiscountPercent(m_oTicket.getLine(i), 0.0);
                    paintTicketLine(i, new DiscountPercent().LineDiscountPercent(oLine, discountrate));
                }
            } else {
                Toolkit.getDefaultToolkit().beep();
            }
        } else {
            Toolkit.getDefaultToolkit().beep();
        }
        refreshTicket();
    }

    private void performDiscountMoney(Double discountmoney) {
        int index = m_ticketlines.getSelectedIndex();
        double total = m_oTicket.getTotal();
        TicketLineInfo oLine;

        if (bTypeDiscountRate == true) {
            if (index >= 0) {
                oLine = new DiscountMoney().LineDiscountMoney(m_oTicket.getLine(index), 0.0);
                paintTicketLine(index, new DiscountMoney().LineDiscountMoney(oLine, discountmoney));
            } else {
                Toolkit.getDefaultToolkit().beep();
            }
        } else if (bTypeDiscountRate == false) {
            if (total > 0.0) {
                for (int i = 0; i < m_oTicket.getLinesCount(); i++) {
                    oLine = new DiscountMoney().LineDiscountMoney(m_oTicket.getLine(i), 0.0);
                    paintTicketLine(i, new DiscountMoney().LineDiscountMoney(oLine, discountmoney / m_oTicket.getLinesCount()));
                }
            } else {
                Toolkit.getDefaultToolkit().beep();
            }
        } else {
            Toolkit.getDefaultToolkit().beep();
        }
        refreshTicket();
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the FormEditor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {
        java.awt.GridBagConstraints gridBagConstraints;

        m_jPanContainer = new javax.swing.JPanel();
        m_jOptions = new javax.swing.JPanel();
        m_jButtons = new javax.swing.JPanel();
        m_jTicketId = new javax.swing.JLabel();
        btnCustomer = new javax.swing.JButton();
        btnSplit = new javax.swing.JButton();
        m_jPanelScripts = new javax.swing.JPanel();
        m_jButtonsExt = new javax.swing.JPanel();
        jPanel1 = new javax.swing.JPanel();
        m_jbtnScale = new javax.swing.JButton();
        m_jPanTicket = new javax.swing.JPanel();
        m_jPanelCentral = new javax.swing.JPanel();
        jPanel4 = new javax.swing.JPanel();
        m_jPanTotals = new javax.swing.JPanel();
        m_jTotalEuros = new javax.swing.JLabel();
        m_jLblTotalEuros1 = new javax.swing.JLabel();
        m_jSubtotalEuros = new javax.swing.JLabel();
        m_jTaxesEuros = new javax.swing.JLabel();
        m_jLblTotalEuros2 = new javax.swing.JLabel();
        m_jLblTotalEuros3 = new javax.swing.JLabel();
        m_jPanelBag = new javax.swing.JPanel();
        m_jEnter = new javax.swing.JButton();
        m_jPrice = new javax.swing.JLabel();
        jPanel5 = new javax.swing.JPanel();
        jPanel2 = new javax.swing.JPanel();
        m_jDelete = new javax.swing.JButton();
        m_jUp = new javax.swing.JButton();
        m_jDown = new javax.swing.JButton();
        m_jList = new javax.swing.JButton();
        m_jEditLine = new javax.swing.JButton();
        jEditAttributes = new javax.swing.JButton();
        m_jContEntries = new javax.swing.JPanel();
        m_jPanEntries = new javax.swing.JPanel();
        m_jNumberKeys = new com.openbravo.beans.JNumberKeys();
        jPanel9 = new javax.swing.JPanel();
        m_jTax = new javax.swing.JComboBox();
        m_jPor = new javax.swing.JLabel();
        m_jaddtax = new javax.swing.JToggleButton();
        m_jKeyFactory = new javax.swing.JTextField();

        setBackground(new java.awt.Color(255, 204, 153));
        setLayout(new java.awt.CardLayout());

        m_jPanContainer.setLayout(new java.awt.BorderLayout());

        m_jOptions.setLayout(new java.awt.BorderLayout());

        m_jTicketId.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        m_jTicketId.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(javax.swing.UIManager.getDefaults().getColor("Button.darkShadow")), javax.swing.BorderFactory.createEmptyBorder(1, 4, 1, 4)));
        m_jTicketId.setOpaque(true);
        m_jTicketId.setPreferredSize(new java.awt.Dimension(160, 25));
        m_jTicketId.setRequestFocusEnabled(false);
        m_jButtons.add(m_jTicketId);

        btnCustomer.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/openbravo/images/kdmconfig32.png"))); // NOI18N
        btnCustomer.setFocusPainted(false);
        btnCustomer.setFocusable(false);
        btnCustomer.setMargin(new java.awt.Insets(0, 4, 0, 4));
        btnCustomer.setRequestFocusEnabled(false);
        btnCustomer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCustomerActionPerformed(evt);
            }
        });
        m_jButtons.add(btnCustomer);

        btnSplit.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/openbravo/images/editcut.png"))); // NOI18N
        btnSplit.setFocusPainted(false);
        btnSplit.setFocusable(false);
        btnSplit.setMargin(new java.awt.Insets(0, 4, 0, 4));
        btnSplit.setRequestFocusEnabled(false);
        btnSplit.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSplitActionPerformed(evt);
            }
        });
        m_jButtons.add(btnSplit);

        m_jOptions.add(m_jButtons, java.awt.BorderLayout.LINE_START);

        m_jPanelScripts.setLayout(new java.awt.BorderLayout());

        m_jButtonsExt.setLayout(new javax.swing.BoxLayout(m_jButtonsExt, javax.swing.BoxLayout.LINE_AXIS));

        m_jbtnScale.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/openbravo/images/ark216.png"))); // NOI18N
        m_jbtnScale.setText(AppLocal.getIntString("button.scale")); // NOI18N
        m_jbtnScale.setFocusPainted(false);
        m_jbtnScale.setFocusable(false);
        m_jbtnScale.setMargin(new java.awt.Insets(0, 4, 0, 4));
        m_jbtnScale.setPreferredSize(new java.awt.Dimension(100, 44));
        m_jbtnScale.setRequestFocusEnabled(false);
        m_jbtnScale.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jbtnScaleActionPerformed(evt);
            }
        });
        jPanel1.add(m_jbtnScale);

        m_jButtonsExt.add(jPanel1);

        m_jPanelScripts.add(m_jButtonsExt, java.awt.BorderLayout.LINE_END);

        m_jOptions.add(m_jPanelScripts, java.awt.BorderLayout.LINE_END);

        m_jPanContainer.add(m_jOptions, java.awt.BorderLayout.NORTH);

        m_jPanTicket.setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 5, 5, 0));
        m_jPanTicket.setLayout(new java.awt.BorderLayout());

        m_jPanelCentral.setLayout(new java.awt.BorderLayout());

        jPanel4.setLayout(new java.awt.BorderLayout());

        m_jPanTotals.setBorder(javax.swing.BorderFactory.createTitledBorder("Totals"));
        m_jPanTotals.setName(""); // NOI18N

        m_jTotalEuros.setFont(new java.awt.Font("Tahoma", 1, 18)); // NOI18N
        m_jTotalEuros.setForeground(new java.awt.Color(0, 0, 255));
        m_jTotalEuros.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
        m_jTotalEuros.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(javax.swing.UIManager.getDefaults().getColor("Button.darkShadow")), javax.swing.BorderFactory.createEmptyBorder(1, 4, 1, 4)));
        m_jTotalEuros.setOpaque(true);
        m_jTotalEuros.setPreferredSize(new java.awt.Dimension(150, 25));
        m_jTotalEuros.setRequestFocusEnabled(false);

        m_jLblTotalEuros1.setFont(new java.awt.Font("Tahoma", 1, 14)); // NOI18N
        m_jLblTotalEuros1.setText(AppLocal.getIntString("label.totalcash")); // NOI18N

        m_jSubtotalEuros.setFont(new java.awt.Font("Tahoma", 1, 18)); // NOI18N
        m_jSubtotalEuros.setForeground(new java.awt.Color(0, 0, 255));
        m_jSubtotalEuros.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
        m_jSubtotalEuros.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(javax.swing.UIManager.getDefaults().getColor("Button.darkShadow")), javax.swing.BorderFactory.createEmptyBorder(1, 4, 1, 4)));
        m_jSubtotalEuros.setOpaque(true);
        m_jSubtotalEuros.setPreferredSize(new java.awt.Dimension(150, 25));
        m_jSubtotalEuros.setRequestFocusEnabled(false);

        m_jTaxesEuros.setFont(new java.awt.Font("Tahoma", 1, 18)); // NOI18N
        m_jTaxesEuros.setForeground(new java.awt.Color(0, 0, 255));
        m_jTaxesEuros.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
        m_jTaxesEuros.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(javax.swing.UIManager.getDefaults().getColor("Button.darkShadow")), javax.swing.BorderFactory.createEmptyBorder(1, 4, 1, 4)));
        m_jTaxesEuros.setOpaque(true);
        m_jTaxesEuros.setPreferredSize(new java.awt.Dimension(150, 25));
        m_jTaxesEuros.setRequestFocusEnabled(false);

        m_jLblTotalEuros2.setFont(new java.awt.Font("Tahoma", 1, 14)); // NOI18N
        m_jLblTotalEuros2.setText(AppLocal.getIntString("label.taxcash")); // NOI18N

        m_jLblTotalEuros3.setFont(new java.awt.Font("Tahoma", 1, 14)); // NOI18N
        m_jLblTotalEuros3.setText(AppLocal.getIntString("label.subtotalcash")); // NOI18N

        javax.swing.GroupLayout m_jPanTotalsLayout = new javax.swing.GroupLayout(m_jPanTotals);
        m_jPanTotals.setLayout(m_jPanTotalsLayout);
        m_jPanTotalsLayout.setHorizontalGroup(
            m_jPanTotalsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(m_jPanTotalsLayout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGroup(m_jPanTotalsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(m_jSubtotalEuros, javax.swing.GroupLayout.DEFAULT_SIZE, 201, Short.MAX_VALUE)
                    .addComponent(m_jLblTotalEuros3))
                .addGap(18, 18, 18)
                .addGroup(m_jPanTotalsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(m_jLblTotalEuros2)
                    .addComponent(m_jTaxesEuros, javax.swing.GroupLayout.DEFAULT_SIZE, 200, Short.MAX_VALUE))
                .addGap(18, 18, 18)
                .addGroup(m_jPanTotalsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(m_jTotalEuros, javax.swing.GroupLayout.PREFERRED_SIZE, 200, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(m_jLblTotalEuros1, javax.swing.GroupLayout.Alignment.TRAILING))
                .addContainerGap())
        );
        m_jPanTotalsLayout.setVerticalGroup(
            m_jPanTotalsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(m_jPanTotalsLayout.createSequentialGroup()
                .addGap(11, 11, 11)
                .addGroup(m_jPanTotalsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(m_jTotalEuros, javax.swing.GroupLayout.DEFAULT_SIZE, 44, Short.MAX_VALUE)
                    .addComponent(m_jSubtotalEuros, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(m_jTaxesEuros, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(m_jPanTotalsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(m_jLblTotalEuros2)
                    .addComponent(m_jLblTotalEuros1)
                    .addComponent(m_jLblTotalEuros3))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jPanel4.add(m_jPanTotals, java.awt.BorderLayout.LINE_END);

        m_jPanelCentral.add(jPanel4, java.awt.BorderLayout.SOUTH);

        m_jPanelBag.setBorder(javax.swing.BorderFactory.createTitledBorder("Barcode"));
        m_jPanelBag.setForeground(new java.awt.Color(0, 0, 255));
        m_jPanelBag.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N

        m_jEnter.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/openbravo/images/barcode.png"))); // NOI18N
        m_jEnter.setFocusPainted(false);
        m_jEnter.setFocusable(false);
        m_jEnter.setRequestFocusEnabled(false);
        m_jEnter.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jEnterActionPerformed(evt);
            }
        });

        m_jPrice.setFont(new java.awt.Font("Tahoma", 1, 24)); // NOI18N
        m_jPrice.setForeground(new java.awt.Color(0, 0, 255));
        m_jPrice.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        m_jPrice.setToolTipText("Barcode");
        m_jPrice.setOpaque(true);
        m_jPrice.setPreferredSize(new java.awt.Dimension(100, 22));
        m_jPrice.setRequestFocusEnabled(false);

        javax.swing.GroupLayout m_jPanelBagLayout = new javax.swing.GroupLayout(m_jPanelBag);
        m_jPanelBag.setLayout(m_jPanelBagLayout);
        m_jPanelBagLayout.setHorizontalGroup(
            m_jPanelBagLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, m_jPanelBagLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(m_jPrice, javax.swing.GroupLayout.DEFAULT_SIZE, 627, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(m_jEnter, javax.swing.GroupLayout.PREFERRED_SIZE, 40, javax.swing.GroupLayout.PREFERRED_SIZE))
        );
        m_jPanelBagLayout.setVerticalGroup(
            m_jPanelBagLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(m_jPanelBagLayout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGroup(m_jPanelBagLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                    .addComponent(m_jEnter, javax.swing.GroupLayout.DEFAULT_SIZE, 43, Short.MAX_VALUE)
                    .addComponent(m_jPrice, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        m_jPanelCentral.add(m_jPanelBag, java.awt.BorderLayout.PAGE_START);

        m_jPanTicket.add(m_jPanelCentral, java.awt.BorderLayout.CENTER);

        jPanel2.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 5, 0, 2));
        jPanel2.setLayout(new java.awt.GridBagLayout());

        m_jDelete.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/openbravo/images/locationbar_erase.png"))); // NOI18N
        m_jDelete.setFocusPainted(false);
        m_jDelete.setFocusable(false);
        m_jDelete.setMaximumSize(new java.awt.Dimension(54, 44));
        m_jDelete.setMinimumSize(new java.awt.Dimension(54, 44));
        m_jDelete.setPreferredSize(new java.awt.Dimension(54, 44));
        m_jDelete.setRequestFocusEnabled(false);
        m_jDelete.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jDeleteActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel2.add(m_jDelete, gridBagConstraints);

        m_jUp.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/openbravo/images/1uparrow22.png"))); // NOI18N
        m_jUp.setFocusPainted(false);
        m_jUp.setFocusable(false);
        m_jUp.setMaximumSize(new java.awt.Dimension(54, 44));
        m_jUp.setMinimumSize(new java.awt.Dimension(54, 44));
        m_jUp.setPreferredSize(new java.awt.Dimension(54, 44));
        m_jUp.setRequestFocusEnabled(false);
        m_jUp.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jUpActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel2.add(m_jUp, gridBagConstraints);

        m_jDown.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/openbravo/images/1downarrow22.png"))); // NOI18N
        m_jDown.setFocusPainted(false);
        m_jDown.setFocusable(false);
        m_jDown.setMaximumSize(new java.awt.Dimension(54, 44));
        m_jDown.setMinimumSize(new java.awt.Dimension(54, 44));
        m_jDown.setPreferredSize(new java.awt.Dimension(54, 44));
        m_jDown.setRequestFocusEnabled(false);
        m_jDown.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jDownActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel2.add(m_jDown, gridBagConstraints);

        m_jList.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/openbravo/images/search22.png"))); // NOI18N
        m_jList.setFocusPainted(false);
        m_jList.setFocusable(false);
        m_jList.setMaximumSize(new java.awt.Dimension(54, 44));
        m_jList.setMinimumSize(new java.awt.Dimension(54, 44));
        m_jList.setPreferredSize(new java.awt.Dimension(54, 44));
        m_jList.setRequestFocusEnabled(false);
        m_jList.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jListActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel2.add(m_jList, gridBagConstraints);

        m_jEditLine.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/openbravo/images/color_line.png"))); // NOI18N
        m_jEditLine.setFocusPainted(false);
        m_jEditLine.setFocusable(false);
        m_jEditLine.setMaximumSize(new java.awt.Dimension(54, 44));
        m_jEditLine.setMinimumSize(new java.awt.Dimension(54, 44));
        m_jEditLine.setPreferredSize(new java.awt.Dimension(54, 44));
        m_jEditLine.setRequestFocusEnabled(false);
        m_jEditLine.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jEditLineActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel2.add(m_jEditLine, gridBagConstraints);

        jEditAttributes.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/openbravo/images/colorize.png"))); // NOI18N
        jEditAttributes.setFocusPainted(false);
        jEditAttributes.setFocusable(false);
        jEditAttributes.setMaximumSize(new java.awt.Dimension(54, 44));
        jEditAttributes.setMinimumSize(new java.awt.Dimension(54, 44));
        jEditAttributes.setPreferredSize(new java.awt.Dimension(54, 44));
        jEditAttributes.setRequestFocusEnabled(false);
        jEditAttributes.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jEditAttributesActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        jPanel2.add(jEditAttributes, gridBagConstraints);

        jPanel5.add(jPanel2);

        m_jPanTicket.add(jPanel5, java.awt.BorderLayout.LINE_END);

        m_jPanContainer.add(m_jPanTicket, java.awt.BorderLayout.CENTER);

        m_jContEntries.setMinimumSize(new java.awt.Dimension(250, 400));
        m_jContEntries.setPreferredSize(new java.awt.Dimension(250, 400));

        m_jNumberKeys.addJNumberEventListener(new com.openbravo.beans.JNumberEventListener() {
            public void keyPerformed(com.openbravo.beans.JNumberEvent evt) {
                m_jNumberKeysKeyPerformed(evt);
            }
        });

        jPanel9.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 5, 5, 5));

        m_jTax.setFocusable(false);
        m_jTax.setRequestFocusEnabled(false);
        jPanel9.add(m_jTax);

        m_jPor.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        m_jPor.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(javax.swing.UIManager.getDefaults().getColor("Button.darkShadow")), javax.swing.BorderFactory.createEmptyBorder(1, 4, 1, 4)));
        m_jPor.setOpaque(true);
        m_jPor.setPreferredSize(new java.awt.Dimension(22, 22));
        m_jPor.setRequestFocusEnabled(false);
        jPanel9.add(m_jPor);

        m_jaddtax.setText("+");
        m_jaddtax.setFocusPainted(false);
        m_jaddtax.setFocusable(false);
        m_jaddtax.setRequestFocusEnabled(false);
        jPanel9.add(m_jaddtax);

        m_jKeyFactory.setBackground(javax.swing.UIManager.getDefaults().getColor("Panel.background"));
        m_jKeyFactory.setForeground(javax.swing.UIManager.getDefaults().getColor("Panel.background"));
        m_jKeyFactory.setBorder(null);
        m_jKeyFactory.setCaretColor(javax.swing.UIManager.getDefaults().getColor("Panel.background"));
        m_jKeyFactory.setPreferredSize(new java.awt.Dimension(1, 1));
        m_jKeyFactory.addKeyListener(new java.awt.event.KeyAdapter() {
            public void keyTyped(java.awt.event.KeyEvent evt) {
                m_jKeyFactoryKeyTyped(evt);
            }
        });

        javax.swing.GroupLayout m_jPanEntriesLayout = new javax.swing.GroupLayout(m_jPanEntries);
        m_jPanEntries.setLayout(m_jPanEntriesLayout);
        m_jPanEntriesLayout.setHorizontalGroup(
            m_jPanEntriesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(m_jPanEntriesLayout.createSequentialGroup()
                .addGap(249, 249, 249)
                .addGroup(m_jPanEntriesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(m_jKeyFactory, javax.swing.GroupLayout.PREFERRED_SIZE, 250, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jPanel9, javax.swing.GroupLayout.PREFERRED_SIZE, 177, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(0, 0, Short.MAX_VALUE))
            .addGroup(m_jPanEntriesLayout.createSequentialGroup()
                .addComponent(m_jNumberKeys, javax.swing.GroupLayout.PREFERRED_SIZE, 243, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(256, 256, 256))
        );
        m_jPanEntriesLayout.setVerticalGroup(
            m_jPanEntriesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(m_jPanEntriesLayout.createSequentialGroup()
                .addComponent(m_jNumberKeys, javax.swing.GroupLayout.PREFERRED_SIZE, 295, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(m_jKeyFactory, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(158, 158, 158)
                .addComponent(jPanel9, javax.swing.GroupLayout.PREFERRED_SIZE, 32, javax.swing.GroupLayout.PREFERRED_SIZE))
        );

        javax.swing.GroupLayout m_jContEntriesLayout = new javax.swing.GroupLayout(m_jContEntries);
        m_jContEntries.setLayout(m_jContEntriesLayout);
        m_jContEntriesLayout.setHorizontalGroup(
            m_jContEntriesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(m_jPanEntries, javax.swing.GroupLayout.PREFERRED_SIZE, 250, javax.swing.GroupLayout.PREFERRED_SIZE)
        );
        m_jContEntriesLayout.setVerticalGroup(
            m_jContEntriesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(m_jContEntriesLayout.createSequentialGroup()
                .addComponent(m_jPanEntries, javax.swing.GroupLayout.PREFERRED_SIZE, 319, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(0, 81, Short.MAX_VALUE))
        );

        m_jPanContainer.add(m_jContEntries, java.awt.BorderLayout.LINE_END);

        add(m_jPanContainer, "ticket");
    }// </editor-fold>//GEN-END:initComponents

    private void m_jbtnScaleActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jbtnScaleActionPerformed

        stateTransition('\u00a7');

    }//GEN-LAST:event_m_jbtnScaleActionPerformed

    private void btnCustomerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCustomerActionPerformed

        JCustomerFinder finder = JCustomerFinder.getCustomerFinder(this, dlCustomers);
        finder.search(m_oTicket.getCustomer());
        finder.setVisible(true);

        try {
            m_oTicket.setCustomer(finder.getSelectedCustomer() == null
                    ? null
                    : dlSales.loadCustomerExt(finder.getSelectedCustomer().getId()));
        } catch (BasicException e) {
            MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotfindcustomer"), e);
            msg.show(this);
        }

        refreshTicket();

}//GEN-LAST:event_btnCustomerActionPerformed

    private void btnSplitActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSplitActionPerformed
        try {
            if (m_oTicket.getLinesCount() > 0) {
                ReceiptSplit splitdialog = ReceiptSplit.getDialog(this, dlSystem.getResourceAsXML("Ticket.Line"), dlSales, dlCustomers, taxeslogic);

                TicketInfo ticket1 = m_oTicket.copyTicket();
                TicketInfo ticket2 = new TicketInfo();
                ticket2.setCustomer(m_oTicket.getCustomer());

                if (splitdialog.showDialog(ticket1, ticket2, m_oTicketExt)) {
                    if (closeTicket(ticket2, m_oTicketExt)) { // already checked  that number of lines > 0
                        setActiveTicket(ticket1, m_oTicketExt);// set result ticket
                    }
                }
            }
        } catch (BasicException ex) {
            new MessageInf(ex).show(this);
        }
}//GEN-LAST:event_btnSplitActionPerformed

    private void m_jKeyFactoryKeyTyped(java.awt.event.KeyEvent evt) {//GEN-FIRST:event_m_jKeyFactoryKeyTyped

        m_jKeyFactory.setText(null);
        stateTransition(evt.getKeyChar());
    }//GEN-LAST:event_m_jKeyFactoryKeyTyped

    private void m_jEnterActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jEnterActionPerformed

        stateTransition('\n');
    }//GEN-LAST:event_m_jEnterActionPerformed

    private void m_jNumberKeysKeyPerformed(com.openbravo.beans.JNumberEvent evt) {//GEN-FIRST:event_m_jNumberKeysKeyPerformed

        stateTransition(evt.getKey());
    }//GEN-LAST:event_m_jNumberKeysKeyPerformed

    private void jEditAttributesActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jEditAttributesActionPerformed

        int i = m_ticketlines.getSelectedIndex();
        if (i < 0) {
            Toolkit.getDefaultToolkit().beep(); // no line selected
        } else {
            try {
                TicketLineInfo line = m_oTicket.getLine(i);
                JProductAttEdit attedit = JProductAttEdit.getAttributesEditor(this, m_App.getSession());
                attedit.editAttributes(line.getProductAttSetId(), line.getProductAttSetInstId());
                attedit.setVisible(true);
                if (attedit.isOK()) {
                    // The user pressed OK
                    line.setProductAttSetInstId(attedit.getAttributeSetInst());
                    line.setProductAttSetInstDesc(attedit.getAttributeSetInstDescription());
                    paintTicketLine(i, line);
                }
            } catch (BasicException ex) {
                MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotfindattributes"), ex);
                msg.show(this);
            }
        }
    }//GEN-LAST:event_jEditAttributesActionPerformed

    private void m_jEditLineActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jEditLineActionPerformed

        int i = m_ticketlines.getSelectedIndex();
        if (i < 0) {
            Toolkit.getDefaultToolkit().beep(); // no line selected
        } else {
            try {
                TicketLineInfo newline = JProductLineEdit.showMessage(this, m_App, m_oTicket.getLine(i));
                if (newline != null) {
                    // line has been modified
                    paintTicketLine(i, newline);
                }
            } catch (BasicException e) {
                new MessageInf(e).show(this);
            }
        }
    }//GEN-LAST:event_m_jEditLineActionPerformed

    private void m_jListActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jListActionPerformed

        ProductInfoExt prod = JProductFinder.showMessage(JPanelTicket.this, dlSales);
        if (prod != null) {
            buttonTransition(prod);
        }
    }//GEN-LAST:event_m_jListActionPerformed

    private void m_jDownActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jDownActionPerformed

        m_ticketlines.selectionDown();
    }//GEN-LAST:event_m_jDownActionPerformed

    private void m_jUpActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jUpActionPerformed

        m_ticketlines.selectionUp();
    }//GEN-LAST:event_m_jUpActionPerformed

    private void m_jDeleteActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jDeleteActionPerformed

        int i = m_ticketlines.getSelectedIndex();
        if (i < 0) {
            Toolkit.getDefaultToolkit().beep(); // No hay ninguna seleccionada
        } else {
            removeTicketLine(i); // elimino la linea
        }
    }//GEN-LAST:event_m_jDeleteActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCustomer;
    private javax.swing.JButton btnSplit;
    private javax.swing.JButton jEditAttributes;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JPanel jPanel5;
    private javax.swing.JPanel jPanel9;
    private javax.swing.JPanel m_jButtons;
    private javax.swing.JPanel m_jButtonsExt;
    private javax.swing.JPanel m_jContEntries;
    private javax.swing.JButton m_jDelete;
    private javax.swing.JButton m_jDown;
    private javax.swing.JButton m_jEditLine;
    private javax.swing.JButton m_jEnter;
    private javax.swing.JTextField m_jKeyFactory;
    private javax.swing.JLabel m_jLblTotalEuros1;
    private javax.swing.JLabel m_jLblTotalEuros2;
    private javax.swing.JLabel m_jLblTotalEuros3;
    private javax.swing.JButton m_jList;
    private com.openbravo.beans.JNumberKeys m_jNumberKeys;
    private javax.swing.JPanel m_jOptions;
    private javax.swing.JPanel m_jPanContainer;
    private javax.swing.JPanel m_jPanEntries;
    private javax.swing.JPanel m_jPanTicket;
    private javax.swing.JPanel m_jPanTotals;
    private javax.swing.JPanel m_jPanelBag;
    private javax.swing.JPanel m_jPanelCentral;
    private javax.swing.JPanel m_jPanelScripts;
    private javax.swing.JLabel m_jPor;
    private javax.swing.JLabel m_jPrice;
    private javax.swing.JLabel m_jSubtotalEuros;
    private javax.swing.JComboBox m_jTax;
    private javax.swing.JLabel m_jTaxesEuros;
    private javax.swing.JLabel m_jTicketId;
    private javax.swing.JLabel m_jTotalEuros;
    private javax.swing.JButton m_jUp;
    private javax.swing.JToggleButton m_jaddtax;
    private javax.swing.JButton m_jbtnScale;
    // End of variables declaration//GEN-END:variables
}
