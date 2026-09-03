package com.marketai.market;

import com.marketai.market.entity.Stock;
import com.marketai.market.repository.StockRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.repository.HoldingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds the stock master (used by search / autocomplete) with popular NSE names
 * plus every symbol the user already holds. Idempotent — only inserts missing symbols.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class StockSeeder implements CommandLineRunner {

    private final StockRepository stockRepo;
    private final HoldingRepository holdingRepo;

    private static final String[][] NSE = {
        {"RELIANCE","Reliance Industries"},{"TCS","Tata Consultancy Services"},{"HDFCBANK","HDFC Bank"},
        {"ICICIBANK","ICICI Bank"},{"INFY","Infosys"},{"HINDUNILVR","Hindustan Unilever"},{"ITC","ITC"},
        {"SBIN","State Bank of India"},{"BHARTIARTL","Bharti Airtel"},{"KOTAKBANK","Kotak Mahindra Bank"},
        {"LT","Larsen & Toubro"},{"AXISBANK","Axis Bank"},{"BAJFINANCE","Bajaj Finance"},{"ASIANPAINT","Asian Paints"},
        {"MARUTI","Maruti Suzuki"},{"HCLTECH","HCL Technologies"},{"SUNPHARMA","Sun Pharmaceutical"},
        {"TITAN","Titan Company"},{"ULTRACEMCO","UltraTech Cement"},{"WIPRO","Wipro"},{"NESTLEIND","Nestle India"},
        {"ONGC","Oil & Natural Gas Corp"},{"NTPC","NTPC"},{"POWERGRID","Power Grid Corp"},{"TATAMOTORS","Tata Motors"},
        {"TMPV","Tata Motors Passenger Vehicles"},{"ADANIENT","Adani Enterprises"},{"ADANIPORTS","Adani Ports & SEZ"},
        {"COALINDIA","Coal India"},{"BAJAJFINSV","Bajaj Finserv"},{"TATASTEEL","Tata Steel"},{"JSWSTEEL","JSW Steel"},
        {"GRASIM","Grasim Industries"},{"HINDALCO","Hindalco Industries"},{"HDFCLIFE","HDFC Life Insurance"},
        {"SBILIFE","SBI Life Insurance"},{"DRREDDY","Dr Reddy's Laboratories"},{"CIPLA","Cipla"},
        {"DIVISLAB","Divi's Laboratories"},{"APOLLOHOSP","Apollo Hospitals"},{"BRITANNIA","Britannia Industries"},
        {"EICHERMOT","Eicher Motors"},{"HEROMOTOCO","Hero MotoCorp"},{"BAJAJ-AUTO","Bajaj Auto"},
        {"M&M","Mahindra & Mahindra"},{"TECHM","Tech Mahindra"},{"INDUSINDBK","IndusInd Bank"},
        {"TATACONSUM","Tata Consumer Products"},{"UPL","UPL"},{"BPCL","Bharat Petroleum"},{"IOC","Indian Oil Corp"},
        {"GAIL","GAIL India"},{"DMART","Avenue Supermarts (DMart)"},{"PIDILITIND","Pidilite Industries"},
        {"DABUR","Dabur India"},{"GODREJCP","Godrej Consumer Products"},{"MARICO","Marico"},{"COLPAL","Colgate-Palmolive"},
        {"HAVELLS","Havells India"},{"SIEMENS","Siemens"},{"ABB","ABB India"},{"BEL","Bharat Electronics"},
        {"HAL","Hindustan Aeronautics"},{"BHEL","Bharat Heavy Electricals"},{"IRCTC","Indian Railway Catering"},
        {"IRFC","Indian Railway Finance Corp"},{"RECLTD","REC"},{"PFC","Power Finance Corp"},{"RVNL","Rail Vikas Nigam"},
        {"IRB","IRB Infrastructure"},{"TITAGARH","Titagarh Rail Systems"},{"BDL","Bharat Dynamics"},{"MAZDOCK","Mazagon Dock"},
        {"COCHINSHIP","Cochin Shipyard"},{"ZOMATO","Zomato (Eternal)"},{"PAYTM","One97 (Paytm)"},{"NYKAA","FSN E-Commerce (Nykaa)"},
        {"POLICYBZR","PB Fintech (Policybazaar)"},{"MOBIKWIK","One MobiKwik Systems"},{"DELHIVERY","Delhivery"},
        {"ANGELONE","Angel One"},{"CDSL","Central Depository Services"},{"BSE","BSE"},{"MCX","Multi Commodity Exchange"},
        {"VBL","Varun Beverages"},{"TRENT","Trent"},{"PGEL","PG Electroplast"},{"DIXON","Dixon Technologies"},
        {"KAYNES","Kaynes Technology"},{"TATAPOWER","Tata Power"},{"ADANIGREEN","Adani Green Energy"},
        {"ADANIPOWER","Adani Power"},{"SUZLON","Suzlon Energy"},{"INOXWIND","Inox Wind"},{"IREDA","IREDA"},
        {"TATAELXSI","Tata Elxsi"},{"PERSISTENT","Persistent Systems"},{"COFORGE","Coforge"},{"LTIM","LTIMindtree"},
        {"MPHASIS","Mphasis"},{"HAPPSTMNDS","Happiest Minds Technologies"},{"TEJASNET","Tejas Networks"},
        {"AUROPHARMA","Aurobindo Pharma"},{"LUPIN","Lupin"},{"BIOCON","Biocon"},{"ZYDUSLIFE","Zydus Lifesciences"},
        {"MANKIND","Mankind Pharma"},{"EMAMILTD","Emami"},{"PGHH","Procter & Gamble Hygiene"},{"PNB","Punjab National Bank"},
        {"BANKBARODA","Bank of Baroda"},{"CANBK","Canara Bank"},{"UNIONBANK","Union Bank of India"},{"IOB","Indian Overseas Bank"},
        {"IDFCFIRSTB","IDFC First Bank"},{"FEDERALBNK","Federal Bank"},{"BANDHANBNK","Bandhan Bank"},{"AUBANK","AU Small Finance Bank"},
        {"UTKARSHBNK","Utkarsh Small Finance Bank"},{"YESBANK","Yes Bank"},{"JIOFIN","Jio Financial Services"},
        {"LICI","Life Insurance Corp"},{"SJVN","SJVN"},{"NHPC","NHPC"},{"STYLEBAAZA","Baazar Style Retail"},
        {"VEDL","Vedanta"},{"JINDALSTEL","Jindal Steel & Power"},{"SAIL","Steel Authority of India"},{"NMDC","NMDC"},
    };

    @Override
    public void run(String... args) {
        int added = 0;
        for (String[] s : NSE) {
            if (s[0].length() <= 20 && !stockRepo.existsBySymbol(s[0])) {
                stockRepo.save(Stock.builder().symbol(s[0]).name(s[1]).exchange("NSE").active(true).build());
                added++;
            }
        }
        // Also make the user's own holdings searchable
        for (Holding h : holdingRepo.findAll()) {
            String sym = h.getSymbol();
            if (sym == null || sym.endsWith(".MF")) continue;
            String base = sym.replace(".NS", "").replace(".BO", "");
            if (base.length() <= 20 && !stockRepo.existsBySymbol(base)) {
                stockRepo.save(Stock.builder().symbol(base)
                    .name(h.getName() != null && !h.getName().isEmpty() ? h.getName() : base)
                    .exchange("NSE").active(true).build());
                added++;
            }
        }
        if (added > 0) log.info("StockSeeder: added {} stocks to master (search/autocomplete)", added);
    }
}
