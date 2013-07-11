/*******************************************************************************
 * Copyright 2013 alladin-IT OG
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package at.alladin.rmbt.mapServer;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.representation.OutputRepresentation;
import org.restlet.representation.Representation;

import at.alladin.rmbt.mapServer.MapServerOptions.MapOption;
import at.alladin.rmbt.mapServer.MapServerOptions.SQLFilter;

public class HeatmapTiles extends TileRestlet
{
    private final static int[] ZOOM_TO_PART_FACTOR = new int[] {
            // factor | zoomlevel
            0, // 0
            0, // 1
            0, // 2
            0, // 3
            0, // 4
            0, // 5
            0, // 6
            1, // 7
            1, // 8
            2, // 9
            2, // 10
            3, // 11
            3, // 12
            4, // 13
            4, // 14
            5, // 15
            5, // 16
            6, // 17
            6, // 18
            7, // 19
            7, // 20
    };
    
    private final static double ALPHA_TOP = 0.5;
    private final static int ALPHA_MAX = 1;
    
    private final static boolean DEBUG_LINES = false;
    
    private final static int HORIZON_OFFSET = 1;
    private final static int HORIZON = HORIZON_OFFSET * 2 + 2;
    private final static int HORIZON_SIZE = HORIZON * HORIZON;
    
    private final static double[][] FACTORS = new double[8][]; // lookup table
                                                               // for speedup
    static
    {
        for (int f = 0; f < 8; f++)
        {
            final int partSize = 1 << f;
            FACTORS[f] = new double[HORIZON_SIZE * partSize * partSize];
            
            for (int i = 0; i < FACTORS[f].length; i += HORIZON_SIZE)
            {
                final double qPi = Math.PI / 4;
                
                final double x = qPi * (i / HORIZON_SIZE % partSize) / partSize;
                final double y = qPi * (i / HORIZON_SIZE / partSize) / partSize;
                
                // double sum = 0;
                for (int j = 0; j < HORIZON; j++)
                    for (int k = 0; k < HORIZON; k++)
                    {
                        final double value = Math.pow(Math.cos(x + (1 - j) * qPi), 2.0)
                                * Math.pow(Math.cos(y + (1 - k) * qPi), 2.0) / 4;
                        FACTORS[f][i + j + k * HORIZON] = value;
                        // sum += value;
                    }
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private final ThreadLocal<int[]>[] pixelBuffers = new ThreadLocal[TILE_SIZES.length];
    
    public HeatmapTiles()
    {
        for (int i = 0; i < TILE_SIZES.length; i++)
        {
            final int tileSize = TILE_SIZES[i];
            pixelBuffers[i] = new ThreadLocal<int[]>()
            {
                @Override
                protected int[] initialValue()
                {
                    return new int[tileSize * tileSize];
                };
            };
        }
    }
    
    @Override
    protected void handle(final Request req, final Response res, final Form params, final int tileSizeIdx, final int zoom, final DBox box,
            final MapOption mo, final List<SQLFilter> filters)
    {
        filters.add(MapServerOptions.getAccuracyMapFilter());
        
        final int tileSize = TILE_SIZES[tileSizeIdx]; 
        
        Connection con = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try
        {
            final String transparencyString = params.getFirstValue("transparency");
            double _transparency = 0.75;
            if (transparencyString != null)
                try
                {
                    _transparency = Double.parseDouble(transparencyString);
                }
                catch (final NumberFormatException e)
                {
                }
            if (_transparency < 0)
                _transparency = 0;
            if (_transparency > 1)
                _transparency = 1;
            final double transparency = _transparency;
            
            final StringBuilder whereSQL = new StringBuilder(mo.sqlFilter);
            for (final SQLFilter sf : filters)
                whereSQL.append(" AND ").append(sf.where);
            
            con = DbConnection.getConnection();
            final String sql = String.format("SELECT count(\"%1$s\") count," 
                    + " quantile(\"%1$s\",?) val,"
                    + " ST_X(ST_SnapToGrid(location, ?,?,?,?)) gx," 
                    + " ST_Y(ST_SnapToGrid(location, ?,?,?,?)) gy"
                    + " FROM test t" 
                    + " WHERE " 
                    + " %2$s"
                    + " AND location && ST_SetSRID(ST_MakeBox2D(ST_Point(?,?), ST_Point(?,?)), 900913)"
                    + " GROUP BY gx,gy", mo.valueColumnLog, whereSQL);
            
            ps = con.prepareStatement(sql);
            
            int i = 1;
            
            float quantil = 0.8f;
            final String statisticalMethod = params.getFirstValue("statistical_method", true);
            if (statisticalMethod != null)
                try
                {
                    final float _quantil = Float.parseFloat(statisticalMethod);
                    if (_quantil >= 0 && _quantil <= 1)
                        quantil = _quantil;
                }
                catch (final NumberFormatException e)
                {
                }
            ps.setFloat(i++, quantil);
            
            // int _partSizeFactor = (int)Math.round((8d/11d) * zoom -
            // (48d/11d));
            // if (_partSizeFactor < 0)
            // _partSizeFactor = 0;
            // if (_partSizeFactor > 7)
            // _partSizeFactor = 7;
            // final int partSizeFactor = _partSizeFactor;
            
            final int partSizeFactor;
            if (zoom >= ZOOM_TO_PART_FACTOR.length)
                partSizeFactor = ZOOM_TO_PART_FACTOR[ZOOM_TO_PART_FACTOR.length - 1];
            else
                partSizeFactor = ZOOM_TO_PART_FACTOR[zoom];
            final int partSizePixels = 1 << partSizeFactor;
            
            // System.out.println(partSizePixels);
            
            final double partSize = box.res * partSizePixels;
            final double origX = box.x1 - box.res * (partSizePixels / 2) - partSize * (HORIZON_OFFSET + 1);
            final double origY = box.y1 - box.res * (partSizePixels / 2) - partSize * (HORIZON_OFFSET + 1);
            for (int j = 0; j < 2; j++)
            {
                ps.setDouble(i++, origX);
                ps.setDouble(i++, origY);
                ps.setDouble(i++, partSize);
                ps.setDouble(i++, partSize);
            }
            
            for (final SQLFilter sf : filters)
                i = sf.fillParams(i, ps);
            
            final double margin = partSize * (HORIZON_OFFSET + 1);
            ps.setDouble(i++, box.x1 - margin);
            ps.setDouble(i++, box.y1 - margin);
            ps.setDouble(i++, box.x2 + margin);
            ps.setDouble(i++, box.y2 + margin);
            
            System.out.println(ps);
            
            if (!ps.execute())
                return;
            
            final int fetchPartsX = tileSize / partSizePixels + (HORIZON_OFFSET + 2) * 2;
            final int fetchPartsY = tileSize / partSizePixels + (HORIZON_OFFSET + 2) * 2;
            
            final double[] values = new double[fetchPartsX * fetchPartsY];
            // final int[] countsReal = new int[fetchPartsX * fetchPartsY];
            final int[] countsRel = new int[fetchPartsX * fetchPartsY];
            
            Arrays.fill(values, Double.NaN);
            
            rs = ps.getResultSet();
            boolean _emptyTile = true;
            while (rs.next())
            {
                _emptyTile = false;
                int count = rs.getInt(1);
                final double val = rs.getDouble(2);
                final double gx = rs.getDouble(3);
                final double gy = rs.getDouble(4);
                
                final int mx = (int) Math.round((gx - origX) / partSize);
                final int my = (int) Math.round((gy - origY) / partSize);
                
                // System.out.println(String.format("%f|%f %d|%d %d %f",gx, gy,
                // mx, my, count, val));
                
                if (mx >= 0 && mx < fetchPartsX && my >= 0 && my < fetchPartsY)
                {
                    final int idx = mx + fetchPartsX * (fetchPartsY - 1 - my);
                    values[idx] = val;
                    // countsReal[idx] = count;
                    if (count > ALPHA_MAX)
                        count = ALPHA_MAX;
                    countsRel[idx] = count;
                }
            }
            
            final boolean emptyTile = _emptyTile;
            
            final Representation output = new OutputRepresentation(MediaType.IMAGE_PNG)
            {
                @Override
                public void write(final OutputStream s) throws IOException
                {
                    if (emptyTile)
                    {
                        s.write(EMPTY_IMAGES[tileSizeIdx]);
                        return;
                    }
                    
                    final Image img = images[tileSizeIdx].get();
                    
                    final int[] pixels = pixelBuffers[tileSizeIdx].get();
                    for (int y = 0; y < tileSize; y++)
                        for (int x = 0; x < tileSize; x++)
                        {
                            final int mx = HORIZON_OFFSET + 1 + (x + partSizePixels / 2) / partSizePixels;
                            final int my = HORIZON_OFFSET + 1 + (y + partSizePixels / 2) / partSizePixels;
                            final int relX = (x + partSizePixels / 2) % partSizePixels;
                            final int relY = (y + partSizePixels / 2) % partSizePixels;
                            final int relOffset = (relY * partSizePixels + relX) * HORIZON_SIZE;
                            
                            double alphaWeigth = 0;
                            double valueWeight = 0;
                            double valueMissing = 0;
                            final int startIdx = mx - HORIZON_OFFSET + fetchPartsX * (my - HORIZON_OFFSET);
                            
                            for (int i = 0; i < HORIZON_SIZE; i++)
                            {
                                final int idx = startIdx + i % HORIZON + fetchPartsX * (i / HORIZON);
                                if (Double.isNaN(values[idx]))
                                    valueMissing += FACTORS[partSizeFactor][i + relOffset];
                                else
                                    valueWeight += FACTORS[partSizeFactor][i + relOffset] * values[idx];
                                alphaWeigth += FACTORS[partSizeFactor][i + relOffset] * countsRel[idx];
                            }
                            
                            if (valueMissing > 0)
                                valueWeight += valueWeight / (1 - valueMissing) * valueMissing;
                            
                            alphaWeigth /= ALPHA_TOP;
                            if (alphaWeigth < 0)
                                alphaWeigth = 0;
                            if (alphaWeigth > 1)
                                alphaWeigth = 1;
                            
                            alphaWeigth *= transparency;
                            
                            final int alpha = (int) (alphaWeigth * 255) << 24;
                            assert alpha >= 0 || alpha <= 255 : alpha;
                            if (alpha == 0)
                                pixels[x + y * tileSize] = 0;
                            else
                                pixels[x + y * tileSize] = valueToColor(mo.colorsSorted, mo.intervalsSorted, valueWeight)
                                        | alpha;
                            // pixels[x + y * WIDTH] = 255 << 24 | alpha >>> 8 |
                            // alpha >>> 16 | alpha >>> 24;
                            
                            if (DEBUG_LINES)
                                if (relX == partSizePixels / 2 || relY == partSizePixels / 2)
                                    pixels[x + y * tileSize] = 0xff000000;
                        }
                    img.bi.setRGB(0, 0, tileSize, tileSize, pixels, 0, tileSize);
                    ImageIO.write(img.bi, "png", s);
                }
            };
            res.setEntity(output);
            
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            try
            {
                if (rs != null)
                    rs.close();
                if (ps != null)
                    ps.close();
                if (con != null)
                    con.close();
            }
            catch (final SQLException e)
            {
                e.printStackTrace();
            }
        }
    }
}
